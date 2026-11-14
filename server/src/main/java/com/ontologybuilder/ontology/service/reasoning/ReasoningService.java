package com.ontologybuilder.ontology.service.reasoning;

import com.ontologybuilder.common.exception.BusinessException;
import com.ontologybuilder.common.util.TtlSyntaxFixer;
import com.ontologybuilder.ontology.model.Ontology;
import com.ontologybuilder.ontology.model.ObjectType;
import com.ontologybuilder.ontology.model.LinkType;
import com.ontologybuilder.oxirs.BcQueryExecutor;
import com.ontologybuilder.oxirs.IncrementalUpdateService;
import com.ontologybuilder.oxirs.OxiRSClient;
import com.ontologybuilder.oxirs.RuleProfile;
import com.ontologybuilder.oxirs.SparqlQueryInterceptor;
import com.ontologybuilder.oxirs.SwrlRuleClassifier;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.Builder;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.apache.jena.query.*;
import org.apache.jena.rdf.model.*;
import org.apache.jena.rdf.model.ModelFactory;
import org.apache.jena.reasoner.Reasoner;
import org.apache.jena.reasoner.rulesys.BuiltinRegistry;
import org.apache.jena.reasoner.rulesys.GenericRuleReasoner;
import org.apache.jena.reasoner.rulesys.Rule;
import org.apache.jena.reasoner.rulesys.RuleContext;
import org.apache.jena.reasoner.rulesys.builtins.BaseBuiltin;
import org.apache.jena.vocabulary.RDF;
import org.apache.jena.tdb2.TDB2;
import org.apache.jena.tdb2.TDB2Factory;
import org.apache.jena.fuseki.main.FusekiServer;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import org.semanticweb.owlapi.apibinding.OWLManager;
import org.semanticweb.owlapi.model.*;
import org.semanticweb.owlapi.reasoner.*;
import org.semanticweb.owlapi.util.ShortFormProvider;
import org.semanticweb.owlapi.util.SimpleShortFormProvider;

import openllet.core.exceptions.InconsistentOntologyException;
import openllet.owlapi.PelletReasonerFactory;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;

import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.BiConsumer;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * 混合推理服务
 * - HermiT: OWL 2 DL 完整一致性检查（与 Protégé 一致）
 * - Jena TDB2: TTL 持久化存储 + SPARQL 查询
 */
@Slf4j
@Service
public class ReasoningService {

    // HermiT ReasonerFactory 候选类名（不同版本的包名可能不同）
    private static final String[] HERMIT_FACTORY_CANDIDATES = {
            "org.semanticweb.HermiT.ReasonerFactory",
            "org.semanticweb.HermiT.ProtegeReasonerFactory",
            "org.semanticweb.hermits.ReasonerFactory",
            "org.hermine.hermits.ReasonerFactory"
    };

    /** Jena GenericRuleReasoner 前向推理超时（秒），防止 OxiRS 不可用时降级路径永久卡死 */
    private static final long JENA_FC_TIMEOUT_SEC = 300;

    /** Jena 降级推理专用 ExecutorService，单线程避免并发资源竞争 */
    private final ExecutorService jenaFcExecutor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "jena-fc-fallback");
        t.setDaemon(true);
        return t;
    });

    @Value("${jena.tdb2.storage-path:./storage/tdb2}")
    private String tdb2StoragePath;

    @Value("${fuseki.embedded.enabled:true}")
    private boolean fusekiEmbeddedEnabled;

    @Value("${fuseki.embedded.port:3030}")
    private int fusekiEmbeddedPort;

    @Value("${fuseki.dataset:ontologybuilder}")
    private String fusekiDatasetName;

    @Autowired(required = false)
    private OxiRSClient oxirsClient;

    private final SwrlRuleClassifier swrlClassifier = new SwrlRuleClassifier();

    /** BC 规则谓词索引: ontologyId → (predicate URI → BC rule profiles) */
    private final Map<String, Map<String, List<RuleProfile>>> bcPredicateIndex = new ConcurrentHashMap<>();

    /** SPARQL 拦截器缓存: ontologyId → interceptor */
    private final Map<String, SparqlQueryInterceptor> interceptorCache = new ConcurrentHashMap<>();

    /** OxiRS 已注册规则 ID 缓存: ontologyId → rule IDs（用于 removeRules 清理旧规则） */
    private final Map<String, java.util.List<String>> registeredRuleIds = new ConcurrentHashMap<>();

    @Value("${oxirs.bc.enabled:false}")
    private boolean bcEnabled;

    @Value("${oxirs.bc.timeout-ms:500}")
    private int bcTimeoutMs;

    @Value("${oxirs.bc.max-depth:10}")
    private int bcMaxDepth;

    /** 是否跳过启动时的本体推理（改为使用时按需推理） */
    @Value("${reasoning.startup.skip:true}")
    private boolean skipStartupReasoning;

    /** TDB2 持久化 Dataset */
    private Dataset tdb2Dataset;

    /** 内嵌 Fuseki Server 实例 */
    private FusekiServer fusekiServer;

    /** TDB2 元数据命名图 URI，用于持久化 Pellet 物化状态 */
    private static final String PELLET_META_GRAPH = "urn:ontologybuilder:pellet-materialized";

    /**
     * Pellet 推理物化缓存: ontologyId → 已物化的推断三元组数
     * 记录哪些本体已经完成过 Pellet 推理物化，避免重复推理。
     */
    private final ConcurrentHashMap<String, Boolean> pelletMaterialized = new ConcurrentHashMap<>();

    /**
     * 每个 ontologyId 对应一把锁，防止并发 Pellet 推理物化导致数据不一致
     */
    private final ConcurrentHashMap<String, ReentrantLock> pelletLocks = new ConcurrentHashMap<>();

    /**
     * 推理物化失败追踪: ontologyId → 失败异常消息
     * 避免每次查询都重试已知会失败的物化（如数据问题导致的 LiteralRequiredException）
     * 仅在 clearAndRematerialize / 重新加载 TTL 时清除
     */
    private final ConcurrentHashMap<String, String> pelletMaterializeFailed = new ConcurrentHashMap<>();

    /** 推理进度追踪: ontologyId → ReasoningProgress */
    private final ConcurrentHashMap<String, ReasoningProgress> reasoningProgressMap = new ConcurrentHashMap<>();

    /** 推理进度信息 */
    public static class ReasoningProgress {
        private int percentage;
        private String phase;

        public ReasoningProgress() {}

        public ReasoningProgress(int percentage, String phase) {
            this.percentage = percentage;
            this.phase = phase;
        }

        public int getPercentage() { return percentage; }
        public void setPercentage(int percentage) { this.percentage = percentage; }
        public String getPhase() { return phase; }
        public void setPhase(String phase) { this.phase = phase; }
    }

    /** 获取指定本体的推理进度（供流水线列表查询） */
    public ReasoningProgress getReasoningProgress(String ontologyId) {
        if (ontologyId == null) return null;
        // 已完成物化但进度记录已清除 → 返回100%
        if (pelletMaterialized.containsKey(ontologyId) && !reasoningProgressMap.containsKey(ontologyId)) {
            return new ReasoningProgress(100, "已完成");
        }
        return reasoningProgressMap.get(ontologyId);
    }

    private void updateReasoningProgress(String ontologyId, int percentage, String phase) {
        ReasoningProgress p = reasoningProgressMap.computeIfAbsent(ontologyId, k -> new ReasoningProgress());
        p.setPercentage(percentage);
        p.setPhase(phase);
    }

    private void clearReasoningProgress(String ontologyId) {
        reasoningProgressMap.remove(ontologyId);
    }

    /**
     * 检查 TDB2 元数据图中是否记录了该本体的 Pellet 物化完成标记
     */
    private boolean isPelletMaterializedInTdb2(String ontologyId) {
        tdb2Dataset.begin(ReadWrite.READ);
        try {
            Model metaModel = tdb2Dataset.getNamedModel(PELLET_META_GRAPH);
            Resource marker = ResourceFactory.createResource("urn:ontologybuilder:ontology:" + ontologyId);
            return metaModel.contains(marker, RDF.type, ResourceFactory.createResource("urn:ontologybuilder:PelletMaterialized"));
        } finally {
            tdb2Dataset.end();
        }
    }

    /**
     * 在 TDB2 元数据图中写入该本体的 Pellet 物化完成标记
     */
    private void markPelletMaterializedInTdb2(String ontologyId) {
        tdb2Dataset.begin(ReadWrite.WRITE);
        try {
            Model metaModel = tdb2Dataset.getNamedModel(PELLET_META_GRAPH);
            Resource marker = ResourceFactory.createResource("urn:ontologybuilder:ontology:" + ontologyId);
            metaModel.add(marker, RDF.type, ResourceFactory.createResource("urn:ontologybuilder:PelletMaterialized"));
            tdb2Dataset.commit();
        } finally {
            tdb2Dataset.end();
        }
    }

    /** 从 TDB2 元数据图中删除物化标记（供 clearAndRematerialize 调用） */
    private void removePelletMaterializedFromTdb2(String ontologyId) {
        tdb2Dataset.begin(ReadWrite.WRITE);
        try {
            Model metaModel = tdb2Dataset.getNamedModel(PELLET_META_GRAPH);
            Resource marker = ResourceFactory.createResource("urn:ontologybuilder:ontology:" + ontologyId);
            metaModel.removeAll(marker, null, null);
            metaModel.removeAll(null, null, marker);
            tdb2Dataset.commit();
            log.info("[Pellet] 已从 TDB2 元数据删除物化标记: {}", ontologyId);
        } catch (Exception e) {
            log.warn("[Pellet] 删除 TDB2 元数据标记失败: {}", e.getMessage());
            tdb2Dataset.abort();
        } finally {
            tdb2Dataset.end();
        }
    }

    /**
     * 从 TDB2 元数据恢复 Pellet 物化状态到内存缓存
     */
    private void restorePelletMaterializedFromTdb2() {
        tdb2Dataset.begin(ReadWrite.READ);
        try {
            if (!tdb2Dataset.containsNamedModel(PELLET_META_GRAPH)) {
                return;
            }
            Model metaModel = tdb2Dataset.getNamedModel(PELLET_META_GRAPH);
            ResIterator subjects = metaModel.listSubjectsWithProperty(
                RDF.type,
                ResourceFactory.createResource("urn:ontologybuilder:PelletMaterialized")
            );
            while (subjects.hasNext()) {
                String uri = subjects.next().getURI();
                if (uri.startsWith("urn:ontologybuilder:ontology:")) {
                    String ontologyId = uri.substring("urn:ontologybuilder:ontology:".length());
                    pelletMaterialized.put(ontologyId, true);
                    log.info("[Pellet] 从 TDB2 元数据恢复物化状态: {}", ontologyId);
                }
            }
        } finally {
            tdb2Dataset.end();
        }
    }

    /**
     * 获取 TDB2 Dataset 实例（供其他 Service 使用）
     */
    public Dataset getDataset() {
        return tdb2Dataset;
    }

    public String getTdb2StoragePath() {
        return tdb2StoragePath;
    }

    /**
     * 将 TTL 内容直接加载到 TDB2 的指定命名图中
     * 先解析 TTL 到内存模型，再在事务中写入 TDB2，避免 getNamedModel 的事务问题
     */
    public long loadTtlIntoTdb2(String ttlContent, String graphUri, boolean overwrite) {
        return loadTtlIntoTdb2(ttlContent, graphUri, overwrite, false);
    }

    /**
     * 将 TTL 内容加载到 TDB2，可选是否跳过推理物化。
     * skipReasoning=true 时仅写入三元组，不触发推理，由调用方异步执行。
     */
    public long loadTtlIntoTdb2(String ttlContent, String graphUri, boolean overwrite, boolean skipReasoning) {
        return loadTtlIntoTdb2(ttlContent, graphUri, overwrite, skipReasoning, null);
    }

    /**
     * 将 TTL 内容加载到 TDB2，可选是否跳过推理物化，支持进度回调。
     * logSink 非 null 时，关键步骤将回传 (level, message) 日志。
     */
    public long loadTtlIntoTdb2(String ttlContent, String graphUri, boolean overwrite, boolean skipReasoning,
                                BiConsumer<String, String> logSink) {
        // Step 0: 预处理TTL内容，修复LLM生成的常见语法问题
        ttlContent = com.ontologybuilder.common.util.TtlSyntaxFixer.fixAll(ttlContent);

        // Step 1: 在事务外解析 TTL 到内存模型
        Model inMemModel = ModelFactory.createDefaultModel();
        try {
            java.io.ByteArrayInputStream bis = new java.io.ByteArrayInputStream(
                    ttlContent.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            inMemModel.read(bis, null, "TURTLE");
        } catch (Exception e) {
            log.error("解析TTL内容失败: {}", e.getMessage());
            throw new BusinessException("TTL内容解析失败: " + e.getMessage());
        }
        long triples = inMemModel.size();
        if (triples == 0) {
            log.warn("TTL内容为空，跳过加载到TDB2");
            if (logSink != null) logSink.accept("warn", "TTL内容为空，跳过加载到TDB2");
            return 0;
        }
        if (logSink != null) logSink.accept("info", "TTL解析成功: " + triples + " 条三元组");

        // Step 2: 在写事务中将内存模型写入 TDB2
        tdb2Dataset.begin(ReadWrite.WRITE);
        try {
            if (graphUri != null && !graphUri.isBlank()) {
                if (overwrite) {
                    tdb2Dataset.replaceNamedModel(graphUri, inMemModel);
                } else {
                    tdb2Dataset.addNamedModel(graphUri, inMemModel);
                }
            } else {
                if (overwrite) {
                    tdb2Dataset.setDefaultModel(inMemModel);
                } else {
                    tdb2Dataset.getDefaultModel().add(inMemModel);
                }
            }
            tdb2Dataset.commit();
            log.info("TTL已加载到TDB2命名图 {}: {} 条三元组 (overwrite={})", graphUri, triples, overwrite);
            if (logSink != null) logSink.accept("success", "TTL已写入TDB2: " + triples + " 条三元组");
        } catch (Exception e) {
            log.error("TTL加载到TDB2失败: {}", e.getMessage());
            tdb2Dataset.abort();
            throw new BusinessException("TTL加载到TDB2失败: " + e.getMessage());
        } finally {
            tdb2Dataset.end();
        }

        if (skipReasoning) {
            log.info("跳过同步推理物化，交由调用方异步执行: graphUri={}", graphUri);
            return triples;
        }

        // 加载完成后触发推理物化
        String modelId = (graphUri != null && !graphUri.isBlank()) ? graphUri : "default";
        if (logSink != null) logSink.accept("info", "开始推理物化: " + modelId);
        if (overwrite) {
            // 全量覆盖：必须重新执行完整推理
            clearAndRematerialize(modelId, logSink);
        } else if (oxirsClient != null && oxirsClient.isEnabled()) {
            // 增量追加：使用 OxiRS 增量更新，避免全量 rematerialize
            log.info("[Incremental] 使用 OxiRS 增量更新模式，跳过全量 rematerialize: {}", modelId);
            if (logSink != null) logSink.accept("info", "使用 OxiRS 增量推理: " + modelId);
            java.util.List<com.ontologybuilder.oxirs.grpc.Rule> fcGrpcRules = getFcGrpcRules(modelId);
            IncrementalUpdateService incrementalService = new IncrementalUpdateService(oxirsClient, fcGrpcRules);
            Model inferred = incrementalService.onTriplesAdded(inMemModel, modelId);
            if (!inferred.isEmpty()) {
                log.info("[Incremental] OxiRS 推断 {} 条新三元组，写回 TDB2", inferred.size());
                if (logSink != null) logSink.accept("info", "OxiRS 增量推理产出: " + inferred.size() + " 条");
                tdb2Dataset.begin(ReadWrite.WRITE);
                try {
                    tdb2Dataset.addNamedModel(modelId, inferred);
                    tdb2Dataset.commit();
                } catch (Exception e) {
                    tdb2Dataset.abort();
                    throw new BusinessException("写回增量推理结果失败: " + e.getMessage());
                } finally {
                    tdb2Dataset.end();
                }
            }
            // Still register BC rules with OxiRS
            materializeJenaSwrlRules(modelId);
        } else {
            clearAndRematerialize(modelId, logSink);
        }
        return triples;
    }

    /**
     * 将少量 TTL 片段增量追加到 TDB2 指定命名图中。
     * 用于知识问答中的知识更新场景（如用户纠错/补充事实），
     * 写入后触发增量推理（OxiRS）或记录待全量推理。
     *
     * @param ttlSnippet 少量 TTL 三元组（如 "prefix: ... <s> <p> <o> ."）
     * @param graphUri   TDB2 命名图 URI（即 ontologyId）
     * @return 新增的三元组数量
     */
    public int loadTtlSnippet(String ttlSnippet, String graphUri) {
        if (ttlSnippet == null || ttlSnippet.isBlank()) {
            return 0;
        }
        // 预处理 TTL 语法
        ttlSnippet = com.ontologybuilder.common.util.TtlSyntaxFixer.fixAll(ttlSnippet);

        // 在事务外解析 TTL 到内存模型
        Model deltaModel = ModelFactory.createDefaultModel();
        try {
            java.io.ByteArrayInputStream bis = new java.io.ByteArrayInputStream(
                    ttlSnippet.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            deltaModel.read(bis, null, "TURTLE");
        } catch (Exception e) {
            log.error("解析 TTL 片段失败: {}", e.getMessage());
            throw new BusinessException("TTL 片段解析失败: " + e.getMessage());
        }

        int triples = (int) deltaModel.size();
        if (triples == 0) {
            log.warn("TTL 片段为空，跳过写入 TDB2");
            return 0;
        }

        // 在写事务中追加到 TDB2
        tdb2Dataset.begin(ReadWrite.WRITE);
        try {
            if (graphUri != null && !graphUri.isBlank()) {
                tdb2Dataset.addNamedModel(graphUri, deltaModel);
            } else {
                tdb2Dataset.getDefaultModel().add(deltaModel);
            }
            tdb2Dataset.commit();
            log.info("TTL 片段已追加到 TDB2 命名图 {}: {} 条三元组", graphUri, triples);
        } catch (Exception e) {
            log.error("TTL 片段写入 TDB2 失败: {}", e.getMessage());
            tdb2Dataset.abort();
            throw new BusinessException("TTL 片段写入 TDB2 失败: " + e.getMessage());
        } finally {
            tdb2Dataset.end();
        }

        // 增量推理：优先使用 OxiRS incremental add，降级为跳过（下次查询前触发全量推理）
        String modelId = (graphUri != null && !graphUri.isBlank()) ? graphUri : "default";
        if (oxirsClient != null && oxirsClient.isEnabled()) {
            try {
                java.util.List<com.ontologybuilder.oxirs.grpc.Rule> fcGrpcRules = getFcGrpcRules(modelId);
                IncrementalUpdateService incrementalService = new IncrementalUpdateService(oxirsClient, fcGrpcRules);
                Model inferred = incrementalService.onTriplesAdded(deltaModel, modelId);
                if (!inferred.isEmpty()) {
                    log.info("[Incremental] OxiRS 增量推理产出 {} 条新三元组，写回 TDB2", inferred.size());
                    tdb2Dataset.begin(ReadWrite.WRITE);
                    try {
                        tdb2Dataset.addNamedModel(modelId, inferred);
                        tdb2Dataset.commit();
                    } catch (Exception e) {
                        tdb2Dataset.abort();
                        log.warn("[Incremental] 写回推理结果失败: {}", e.getMessage());
                    } finally {
                        tdb2Dataset.end();
                    }
                }
            } catch (Exception e) {
                log.warn("[Incremental] 增量推理失败，将在下次查询时触发全量推理: {}", e.getMessage());
            }
        } else {
            log.info("OxiRS 未启用，跳过增量推理。新增三元组将在下次全量推理时被覆盖。");
        }

        return triples;
    }

    /**
     * 从 TDB2 命名图中删除指定的三元组片段（用于知识更新的旧值覆盖）。
     * 与 loadTtlSnippet 对称：解析 TTL → 在写事务中从命名图中移除。
     *
     * @return 成功删除的三元组数量
     */
    public int deleteTtlSnippet(String ttlSnippet, String graphUri) {
        if (ttlSnippet == null || ttlSnippet.isBlank()) {
            return 0;
        }
        ttlSnippet = com.ontologybuilder.common.util.TtlSyntaxFixer.fixAll(ttlSnippet);

        Model deltaModel = ModelFactory.createDefaultModel();
        try {
            java.io.ByteArrayInputStream bis = new java.io.ByteArrayInputStream(
                    ttlSnippet.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            deltaModel.read(bis, null, "TURTLE");
        } catch (Exception e) {
            log.error("解析删除 TTL 片段失败: {}", e.getMessage());
            throw new BusinessException("删除 TTL 片段解析失败: " + e.getMessage());
        }

        int triples = (int) deltaModel.size();
        if (triples == 0) {
            log.warn("删除 TTL 片段为空，跳过");
            return 0;
        }

        tdb2Dataset.begin(ReadWrite.WRITE);
        try {
            if (graphUri != null && !graphUri.isBlank()) {
                Model namedModel = tdb2Dataset.getNamedModel(graphUri);
                namedModel.remove(deltaModel);
            } else {
                tdb2Dataset.getDefaultModel().remove(deltaModel);
            }
            tdb2Dataset.commit();
            log.info("从 TDB2 命名图 {} 删除 {} 条三元组", graphUri, triples);
        } catch (Exception e) {
            log.error("从 TDB2 删除三元组失败: {}", e.getMessage());
            tdb2Dataset.abort();
            throw new BusinessException("TDB2 删除三元组失败: " + e.getMessage());
        } finally {
            tdb2Dataset.end();
        }

        return triples;
    }

    /**
     * 初始化 TDB2 Dataset
     */
    @PostConstruct
    public void init() {
        try {
            // 注册自定义 SWRL builtin（一次性全局注册）
            BuiltinRegistry.theRegistry.register(new SwrlBound());
            BuiltinRegistry.theRegistry.register(new StringEqualIgnoreCase());
            BuiltinRegistry.theRegistry.register(new SwrlAbs());
            BuiltinRegistry.theRegistry.register(new SwrlNot());
            BuiltinRegistry.theRegistry.register(new SwrlStrContains());

            Files.createDirectories(Paths.get(tdb2StoragePath));
            tdb2Dataset = TDB2Factory.connectDataset(tdb2StoragePath);
            // 启用 union default graph，让直接查询默认图的 SPARQL 能命中所有命名图
            tdb2Dataset.getContext().set(TDB2.symUnionDefaultGraph, true);
            log.info("Jena TDB2 初始化成功, 存储路径: {}", tdb2StoragePath);

            // 恢复已有的命名模型列表（必须在事务内执行），并触发 Pellet 推理物化
            List<String> existingModels = new ArrayList<>();
            tdb2Dataset.begin(ReadWrite.READ);
            try {
                Iterator<String> names = tdb2Dataset.listNames();
                while (names.hasNext()) {
                    existingModels.add(names.next());
                }
            } finally {
                tdb2Dataset.end();
            }
            if (!existingModels.isEmpty()) {
                log.info("TDB2 已有 {} 个持久化本体: {}", existingModels.size(), existingModels);
            }

            // 从 TDB2 元数据恢复 Pellet 物化状态，避免重启后重复推理
            restorePelletMaterializedFromTdb2();

            // 启动内嵌 Fuseki Server（共享同一个 TDB2 Dataset）
            if (fusekiEmbeddedEnabled) {
                startEmbeddedFuseki();
            }

            // 对所有已加载但尚未物化的本体触发 Pellet 推理物化（可配置跳过）
            if (skipStartupReasoning) {
                log.info("[Pellet] 跳过启动时的本体推理（配置 reasoning.startup.skip=true），改为使用时按需推理");
            } else {
                for (String modelId : existingModels) {
                    if (pelletMaterialized.containsKey(modelId)) {
                        log.info("[Pellet] 本体 {} 已有物化标记，跳过启动推理", modelId);
                        continue;
                    }
                    try {
                        materializePelletInferences(modelId);
                    } catch (Exception e) {
                        log.warn("[Pellet] 启动时推理物化失败 ({}): {}", modelId, e.getMessage());
                    }
                }
            }
        } catch (Exception e) {
            log.error("TDB2 初始化失败", e);
            throw new RuntimeException("Jena TDB2 初始化失败: " + e.getMessage(), e);
        }
    }

    /**
     * 关闭内嵌 Fuseki Server 和 TDB2 Dataset
     */
    @PreDestroy
    public void destroy() {
        if (fusekiServer != null) {
            try {
                fusekiServer.stop();
                log.info("内嵌 Fuseki Server 已停止 (端口 {})", fusekiEmbeddedPort);
            } catch (Exception e) {
                log.warn("Fuseki Server 停止异常", e);
            }
        }
        if (tdb2Dataset != null) {
            try {
                tdb2Dataset.close();
                log.info("TDB2 Dataset 已关闭");
            } catch (Exception e) {
                log.warn("TDB2 关闭异常", e);
            }
        }
        if (jenaFcExecutor != null) {
            jenaFcExecutor.shutdownNow();
        }
    }

    /**
     * 启动内嵌 Fuseki Server，与 TDB2 共享同一个 Dataset
     * 提供 SPARQL HTTP 端点和 Fuseki Web UI
     */
    private void startEmbeddedFuseki() {
        try {
            fusekiServer = FusekiServer.create()
                    .port(fusekiEmbeddedPort)
                    .loopback(false)
                    .add("/" + fusekiDatasetName, tdb2Dataset)
                    .verbose(true)
                    .build();
            fusekiServer.start();
            log.info("内嵌 Fuseki Server 启动成功: http://localhost:{}/{}/sparql", fusekiEmbeddedPort, fusekiDatasetName);
            log.info("Fuseki Web UI: http://localhost:{}/", fusekiEmbeddedPort);
        } catch (Throwable t) {
            log.warn("内嵌 Fuseki Server 启动失败 (端口 {} 可能被占用，不影响主服务): {}", fusekiEmbeddedPort, t.getMessage());
        }
    }

    // ==================== HermiT: OWL 2 DL 推理 ====================

    /**
     * 通过反射创建 HermiT OWLReasonerFactory
     */
    @SuppressWarnings("unchecked")
    private OWLReasonerFactory createHermiTFactory() {
        for (String className : HERMIT_FACTORY_CANDIDATES) {
            try {
                Class<? extends OWLReasonerFactory> clazz =
                        (Class<? extends OWLReasonerFactory>) Class.forName(className);
                log.info("成功加载 HermiT 推理器: {}", className);
                return clazz.getDeclaredConstructor().newInstance();
            } catch (ClassNotFoundException ignored) {
                // 尝试下一个候选
            } catch (Exception e) {
                throw new BusinessException("HermiT 推理器初始化失败 (" + className + "): " + e.getMessage());
            }
        }
        throw new BusinessException("HermiT 推理器未找到，请确认 hermit 依赖已正确添加到 pom.xml");
    }

    /**
     * 使用 HermiT 对 TTL 文件执行 OWL 2 DL 一致性检查
     */
    public HermiTCheckResult checkConsistencyWithHermiT(String ttlPath) {
        try {
            log.info("使用 HermiT 执行 OWL 2 DL 一致性检查: {}", ttlPath);

            OWLOntologyManager manager = OWLManager.createOWLOntologyManager();
            OWLOntology ontology;

            if (ttlPath != null && Files.exists(Paths.get(ttlPath))) {
                ontology = manager.loadOntologyFromOntologyDocument(
                        IRI.create(Paths.get(ttlPath).toUri()));
            } else {
                throw new BusinessException("TTL 文件不存在: " + ttlPath);
            }

            return doHermiTCheck(ontology);

        } catch (BusinessException e) {
            throw e;
        } catch (OWLOntologyCreationException e) {
            log.error("HermiT 推理失败: 无法加载本体", e);
            throw new BusinessException("HermiT 推理失败: 无法加载本体 - " + e.getMessage());
        } catch (Exception e) {
            log.error("HermiT 推理失败", e);
            throw new BusinessException("HermiT 推理失败: " + e.getMessage());
        }
    }

    /**
     * 使用 HermiT 对数据库中的本体数据执行 OWL 2 DL 一致性检查
     */
    public HermiTCheckResult checkConsistencyWithHermiT(Ontology ontology, List<ObjectType> objectTypes, List<LinkType> linkTypes) {
        try {
            log.info("使用 HermiT 检查本体一致性: {} ({})", ontology.getName(), ontology.getId());

            OWLOntologyManager manager = OWLManager.createOWLOntologyManager();
            OWLDataFactory df = manager.getOWLDataFactory();

            String baseUri = "http://ontologybuilder.local/ontology/" + ontology.getId() + "#";
            IRI ontologyIri = IRI.create(baseUri.substring(0, baseUri.length() - 1));

            OWLOntology owlOntology = manager.createOntology(ontologyIri);

            // 添加本体注解
            if (ontology.getName() != null) {
                OWLAnnotation labelAnnotation = df.getOWLAnnotation(df.getRDFSLabel(),
                        df.getOWLLiteral(ontology.getName(), "zh"));
                manager.applyChange(new AddOntologyAnnotation(owlOntology, labelAnnotation));
            }
            if (ontology.getDescription() != null) {
                OWLAnnotation commentAnnotation = df.getOWLAnnotation(df.getRDFSComment(),
                        df.getOWLLiteral(ontology.getDescription(), "zh"));
                manager.applyChange(new AddOntologyAnnotation(owlOntology, commentAnnotation));
            }

            // 构建 IRI -> OWLClass 映射
            Map<String, OWLClass> classMap = new HashMap<>();
            for (ObjectType ot : objectTypes) {
                String iriStr = (ot.getIri() != null && !ot.getIri().isEmpty())
                        ? ot.getIri() : baseUri + "Class_" + ot.getId();
                OWLClass cls = df.getOWLClass(IRI.create(iriStr));
                classMap.put(iriStr, cls);
                classMap.put(ot.getId(), cls);

                manager.addAxiom(owlOntology, df.getOWLDeclarationAxiom(cls));
                if (ot.getLabel() != null) {
                    OWLAnnotationAssertionAxiom labelAxiom = df.getOWLAnnotationAssertionAxiom(
                            cls.getIRI(), df.getOWLAnnotation(df.getRDFSLabel(),
                                    df.getOWLLiteral(ot.getLabel(), "zh")));
                    manager.addAxiom(owlOntology, labelAxiom);
                }
            }

            // 添加子类关系
            for (ObjectType ot : objectTypes) {
                if (ot.getParentIri() != null && !ot.getParentIri().isEmpty()) {
                    OWLClass child = classMap.get(ot.getId());
                    OWLClass parent = classMap.get(ot.getParentIri());
                    if (child != null && parent != null) {
                        manager.addAxiom(owlOntology, df.getOWLSubClassOfAxiom(child, parent));
                    }
                }
            }

            // 添加对象属性（注意：importTtl 中 DatatypeProperty 也被存入 LinkType，
            // 需要过滤掉 range 为 XSD 数据类型的属性，避免同一个 IRI 被同时声明为
            // OWLObjectProperty 和 OWLDataProperty 导致 Pellet 推理不一致）
            Set<String> xsdDataPropIris = new HashSet<>();
            for (LinkType lt : linkTypes) {
                String rangeType = lt.getTargetType();
                // 如果 range 是 XSD 数据类型（如 xsd:string），则该属性实际上是数据属性
                if (rangeType != null && (rangeType.startsWith("http://www.w3.org/2001/XMLSchema#")
                        || rangeType.startsWith("http://www.w3.org/2000/10/XMLSchema#")
                        || rangeType.startsWith("http://www.w3.org/2001/XMLSchema#"))) {
                    xsdDataPropIris.add(lt.getIri());
                }
            }

            for (LinkType lt : linkTypes) {
                String propIriStr = (lt.getIri() != null && !lt.getIri().isEmpty())
                        ? lt.getIri() : baseUri + "Property_" + lt.getId();

                // 跳过数据属性（range 为 XSD 类型的属性由 propertiesJson 或 TTL 负责声明）
                if (xsdDataPropIris.contains(lt.getIri())) {
                    log.debug("跳过数据属性（range为XSD类型）: {} -> {}", propIriStr, lt.getTargetType());
                    continue;
                }

                OWLObjectProperty prop = df.getOWLObjectProperty(IRI.create(propIriStr));

                manager.addAxiom(owlOntology, df.getOWLDeclarationAxiom(prop));
                if (lt.getLabel() != null) {
                    OWLAnnotationAssertionAxiom labelAxiom = df.getOWLAnnotationAssertionAxiom(
                            prop.getIRI(), df.getOWLAnnotation(df.getRDFSLabel(),
                                    df.getOWLLiteral(lt.getLabel(), "zh")));
                    manager.addAxiom(owlOntology, labelAxiom);
                }

                if (lt.getSourceType() != null && !lt.getSourceType().isEmpty()) {
                    OWLClass domain = classMap.get(lt.getSourceType());
                    if (domain != null) {
                        manager.addAxiom(owlOntology, df.getOWLObjectPropertyDomainAxiom(prop, domain));
                    }
                }
                if (lt.getTargetType() != null && !lt.getTargetType().isEmpty()) {
                    OWLClass range = classMap.get(lt.getTargetType());
                    if (range != null) {
                        manager.addAxiom(owlOntology, df.getOWLObjectPropertyRangeAxiom(prop, range));
                    }
                }
            }

            // 添加数据属性（DatatypeProperty）从 ObjectType 的 propertiesJson 中提取
            com.fasterxml.jackson.databind.ObjectMapper jsonMapper = new com.fasterxml.jackson.databind.ObjectMapper();
            Set<String> dbDataPropIriSet = new HashSet<>();  // 记录从 DB 构建的数据属性 IRI，避免与 TTL 加载重复
            for (ObjectType ot : objectTypes) {
                if (ot.getPropertiesJson() != null && !ot.getPropertiesJson().isBlank()) {
                    try {
                        java.util.List<java.util.Map<String, String>> dataProps = jsonMapper.readValue(
                                ot.getPropertiesJson(), java.util.List.class);
                        OWLClass domainCls = classMap.get(ot.getIri() != null ? ot.getIri() : ot.getId());
                        for (java.util.Map<String, String> dp : dataProps) {
                            String dpIri = dp.getOrDefault("iri", baseUri + "dp_" + java.util.UUID.randomUUID().toString().substring(0, 8));
                            OWLDataProperty dataProp = df.getOWLDataProperty(IRI.create(dpIri));
                            manager.addAxiom(owlOntology, df.getOWLDeclarationAxiom(dataProp));
                            dbDataPropIriSet.add(dpIri);
                            String dpLabel = dp.get("label");
                            if (dpLabel != null && !dpLabel.isBlank()) {
                                OWLAnnotationAssertionAxiom labelAxiom = df.getOWLAnnotationAssertionAxiom(
                                        dataProp.getIRI(), df.getOWLAnnotation(df.getRDFSLabel(),
                                                df.getOWLLiteral(dpLabel, "zh")));
                                manager.addAxiom(owlOntology, labelAxiom);
                            }
                            if (domainCls != null) {
                                manager.addAxiom(owlOntology, df.getOWLDataPropertyDomainAxiom(dataProp, domainCls));
                            }
                            String dpRange = expandXsdTypeUri(dp.get("range"));
                            if (dpRange != null && !dpRange.isBlank()) {
                                OWLDatatype datatype = df.getOWLDatatype(IRI.create(dpRange));
                                manager.addAxiom(owlOntology, df.getOWLDataPropertyRangeAxiom(dataProp, datatype));
                            } else {
                                manager.addAxiom(owlOntology, df.getOWLDataPropertyRangeAxiom(dataProp, df.getStringOWLDatatype()));
                            }
                        }
                    } catch (Exception e) {
                        log.warn("解析 ObjectType {} 的 propertiesJson 失败: {}", ot.getId(), e.getMessage());
                    }
                }
            }

            // 尝试从 TTL 文件中加载个体（Named Individuals）和数据属性声明以支持实例推理
            if (ontology.getTtlPath() != null && Files.exists(Paths.get(ontology.getTtlPath()))) {
                try {
                    OWLOntologyManager ttlManager = OWLManager.createOWLOntologyManager();
                    OWLOntology ttlOntology = ttlManager.loadOntologyFromOntologyDocument(
                            IRI.create(Paths.get(ontology.getTtlPath()).toUri()));
                    int individualCount = 0;
                    int dpCount = 0;
                    // 添加个体、数据属性声明和相关公理，避免与已构建的类/对象属性定义冲突
                    for (OWLAxiom axiom : ttlOntology.getAxioms()) {
                        if (axiom instanceof OWLDeclarationAxiom) {
                            OWLDeclarationAxiom decl = (OWLDeclarationAxiom) axiom;
                            if (decl.getEntity() instanceof OWLNamedIndividual) {
                                manager.addAxiom(owlOntology, decl);
                                individualCount++;
                            } else if (decl.getEntity() instanceof OWLDataProperty) {
                                // 跳过已从 propertiesJson 构建的数据属性声明，避免重复
                                String propIri = decl.getEntity().getIRI().toString();
                                if (!dbDataPropIriSet.contains(propIri)) {
                                    manager.addAxiom(owlOntology, decl);
                                    dpCount++;
                                }
                            }
                        } else if (axiom instanceof OWLClassAssertionAxiom
                                || axiom instanceof OWLObjectPropertyAssertionAxiom
                                || axiom instanceof OWLDataPropertyAssertionAxiom
                                || axiom instanceof OWLDataPropertyDomainAxiom) {
                            manager.addAxiom(owlOntology, axiom);
                        } else if (axiom instanceof OWLDataPropertyRangeAxiom) {
                            // 跳过已从 propertiesJson 构建的 range 声明，避免重复或冲突
                            OWLDataPropertyRangeAxiom rangeAxiom = (OWLDataPropertyRangeAxiom) axiom;
                            String propIri = rangeAxiom.getProperty().asOWLDataProperty().getIRI().toString();
                            if (!dbDataPropIriSet.contains(propIri)) {
                                manager.addAxiom(owlOntology, axiom);
                            }
                        } else if (axiom instanceof OWLSubDataPropertyOfAxiom) {
                            manager.addAxiom(owlOntology, axiom);
                        }
                    }
                    if (individualCount > 0 || dpCount > 0) {
                        log.info("从 TTL 文件加载了 {} 个个体和 {} 个数据属性声明到本体中，用于推理", individualCount, dpCount);
                    }
                } catch (Exception e) {
                    log.warn("加载 TTL 文件中的个体和数据属性失败，继续使用 DB 数据进行推理: {}", e.getMessage());
                }
            }

            return doHermiTCheck(owlOntology);

        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            log.error("HermiT 推理失败", e);
            throw new BusinessException("HermiT 推理失败: " + e.getMessage());
        }
    }

    /**
     * 核心推理逻辑：使用 HermiT 对 OWL API 本体执行一致性检查
     */
    private HermiTCheckResult doHermiTCheck(OWLOntology owlOntology) {
        try {
            OWLReasonerFactory hermitFactory = createHermiTFactory();
            OWLReasoner reasoner = hermitFactory.createReasoner(owlOntology);
            OWLDataFactory df = owlOntology.getOWLOntologyManager().getOWLDataFactory();

            long startPrep = System.currentTimeMillis();
            reasoner.precomputeInferences(InferenceType.CLASS_HIERARCHY,
                    InferenceType.OBJECT_PROPERTY_HIERARCHY,
                    InferenceType.DATA_PROPERTY_HIERARCHY);
            long prepTime = System.currentTimeMillis() - startPrep;
            log.info("HermiT 预计算完成, 耗时: {}ms", prepTime);

            boolean consistent = reasoner.isConsistent();
            log.info("HermiT 一致性检查结果: {}", consistent);

            List<String> inconsistencies = new ArrayList<>();
            List<String> unsatisfiableClasses = new ArrayList<>();
            Map<String, List<String>> inferredHierarchy = new LinkedHashMap<>();
            Map<String, List<String>> inferredPropertyHierarchy = new LinkedHashMap<>();

            if (!consistent) {
                inconsistencies.add("本体不一致: 存在逻辑矛盾，请检查类定义、属性约束或个体声明");

                // 尝试获取不一致的解释
                try {
                    for (OWLClass cls : owlOntology.getClassesInSignature()) {
                        if (!reasoner.isSatisfiable(cls)) {
                            String name = new SimpleShortFormProvider().getShortForm(cls);
                            unsatisfiableClasses.add(name + " (" + cls.getIRI() + ")");
                        }
                    }
                } catch (Exception ignored) {}
            }

            // 检查不可满足类
            if (consistent) {
                ShortFormProvider sfp = new SimpleShortFormProvider();
                for (OWLClass cls : owlOntology.getClassesInSignature()) {
                    if (cls.isOWLThing() || cls.isOWLNothing()) continue;
                    if (!reasoner.isSatisfiable(cls)) {
                        String name = sfp.getShortForm(cls);
                        unsatisfiableClasses.add(name + " (" + cls.getIRI() + ")");
                        inconsistencies.add("不可满足类: " + name + " 被推断为等同于 owl:Nothing");
                    }
                }
            }

            // 推断类层次结构
            ShortFormProvider sfp = new SimpleShortFormProvider();
            for (OWLClass cls : owlOntology.getClassesInSignature()) {
                if (cls.isOWLThing() || cls.isOWLNothing()) continue;
                NodeSet<OWLClass> subClasses = reasoner.getSubClasses(cls, false);
                List<String> subClassNames = subClasses.entities()
                        .filter(c -> !c.isOWLNothing())
                        .map(c -> sfp.getShortForm(c))
                        .sorted()
                        .collect(Collectors.toList());
                if (!subClassNames.isEmpty()) {
                    inferredHierarchy.put(sfp.getShortForm(cls), subClassNames);
                }
            }

            // 推断对象属性层次
            for (OWLObjectProperty prop : owlOntology.getObjectPropertiesInSignature()) {
                if (prop.isOWLTopObjectProperty() || prop.isOWLBottomObjectProperty()) continue;
                NodeSet<OWLObjectPropertyExpression> subProps = reasoner.getSubObjectProperties(prop, false);
                List<String> subPropNames = subProps.entities()
                        .filter(p -> !p.isOWLBottomObjectProperty())
                        .map(p -> p instanceof OWLObjectProperty ? sfp.getShortForm((OWLObjectProperty) p) : p.toString())
                        .sorted()
                        .collect(Collectors.toList());
                if (!subPropNames.isEmpty()) {
                    inferredPropertyHierarchy.put(sfp.getShortForm(prop), subPropNames);
                }
            }

            // ==================== 能力1增强: 本体一致性检测 ====================

            // 等价类检测 — 发现语义上等价的概念
            Map<String, List<String>> equivalentClasses = new LinkedHashMap<>();
            for (OWLClass cls : owlOntology.getClassesInSignature()) {
                if (cls.isOWLThing() || cls.isOWLNothing()) continue;
                try {
                    Node<OWLClass> equiv = reasoner.getEquivalentClasses(cls);
                    List<String> equivNames = equiv.entities()
                            .filter(c -> !c.equals(cls) && !c.isOWLThing() && !c.isOWLNothing())
                            .map(c -> sfp.getShortForm(c))
                            .sorted()
                            .collect(Collectors.toList());
                    if (!equivNames.isEmpty()) {
                        equivalentClasses.put(sfp.getShortForm(cls), equivNames);
                    }
                } catch (Exception ignored) {}
            }

            // 互斥类检测 — 发现不能同时属于的概念
            Map<String, List<String>> disjointClasses = new LinkedHashMap<>();
            for (OWLClass cls : owlOntology.getClassesInSignature()) {
                if (cls.isOWLThing() || cls.isOWLNothing()) continue;
                try {
                    NodeSet<OWLClass> disjoint = reasoner.getDisjointClasses(cls);
                    List<String> disjointNames = disjoint.entities()
                            .filter(c -> !c.isOWLNothing())
                            .map(c -> sfp.getShortForm(c))
                            .sorted()
                            .collect(Collectors.toList());
                    if (!disjointNames.isEmpty()) {
                        disjointClasses.put(sfp.getShortForm(cls), disjointNames);
                    }
                } catch (Exception ignored) {}
            }

            // 如果存在互斥类冲突（某个不可满足类参与了互斥关系）
            if (!consistent && !unsatisfiableClasses.isEmpty()) {
                for (OWLClass cls : owlOntology.getClassesInSignature()) {
                    if (cls.isOWLThing() || cls.isOWLNothing()) continue;
                    if (!reasoner.isSatisfiable(cls)) {
                        try {
                            NodeSet<OWLClass> disjoint = reasoner.getDisjointClasses(cls);
                            for (OWLClass dc : disjoint.entities().collect(Collectors.toList())) {
                                if (!dc.isOWLNothing()) {
                                    inconsistencies.add("互斥冲突: " + sfp.getShortForm(cls)
                                            + " 与 " + sfp.getShortForm(dc) + " 声明互斥，但存在个体同时属于两者");
                                }
                            }
                        } catch (Exception ignored) {}
                    }
                }
            }

            // ==================== 能力2增强: 类层级关系推理 ====================

            // 推断超类 — 发现隐含的父类关系
            Map<String, List<String>> inferredSuperClasses = new LinkedHashMap<>();
            for (OWLClass cls : owlOntology.getClassesInSignature()) {
                if (cls.isOWLThing() || cls.isOWLNothing()) continue;
                try {
                    NodeSet<OWLClass> superClasses = reasoner.getSuperClasses(cls, false);
                    List<String> superClassNames = superClasses.entities()
                            .filter(c -> !c.isOWLThing())
                            .map(c -> sfp.getShortForm(c))
                            .sorted()
                            .collect(Collectors.toList());
                    if (!superClassNames.isEmpty()) {
                        inferredSuperClasses.put(sfp.getShortForm(cls), superClassNames);
                    }
                } catch (Exception ignored) {}
            }

            // ==================== 能力3: 实例归属判断 ====================

            Map<String, List<String>> inferredIndividualTypes = new LinkedHashMap<>();
            Map<String, List<String>> classInstances = new LinkedHashMap<>();
            int totalIndividuals = 0;

            try {
                for (OWLNamedIndividual individual : owlOntology.getIndividualsInSignature()) {
                    totalIndividuals++;
                    try {
                        // 获取个体的推断类型（直接+间接）
                        NodeSet<OWLClass> types = reasoner.getTypes(individual, false);
                        List<String> typeNames = types.entities()
                                .filter(c -> !c.isOWLThing())
                                .map(c -> sfp.getShortForm(c))
                                .sorted()
                                .collect(Collectors.toList());
                        if (!typeNames.isEmpty()) {
                            inferredIndividualTypes.put(sfp.getShortForm(individual), typeNames);
                        }
                    } catch (Exception ignored) {}
                }

                // 获取每个类的所有实例
                for (OWLClass cls : owlOntology.getClassesInSignature()) {
                    if (cls.isOWLThing() || cls.isOWLNothing()) continue;
                    try {
                        NodeSet<OWLNamedIndividual> instances = reasoner.getInstances(cls, false);
                        List<String> instanceNames = instances.entities()
                                .map(i -> sfp.getShortForm(i))
                                .sorted()
                                .collect(Collectors.toList());
                        if (!instanceNames.isEmpty()) {
                            classInstances.put(sfp.getShortForm(cls), instanceNames);
                        }
                    } catch (Exception ignored) {}
                }
            } catch (Exception e) {
                log.warn("实例推理部分失败: {}", e.getMessage());
            }

            // ==================== 能力4: 属性与关系推导 ====================

            Map<String, List<String>> propertyCharacteristics = new LinkedHashMap<>();
            Map<String, String> inverseProperties = new LinkedHashMap<>();
            Map<String, List<String>> equivalentProperties = new LinkedHashMap<>();

            for (OWLObjectProperty prop : owlOntology.getObjectPropertiesInSignature()) {
                if (prop.isOWLTopObjectProperty() || prop.isOWLBottomObjectProperty()) continue;
                String propName = sfp.getShortForm(prop);
                List<String> characteristics = new ArrayList<>();

                try {
                    // 检测各种属性特征（包括通过推理隐含获得的特征）
                    if (reasoner.isEntailed(df.getOWLFunctionalObjectPropertyAxiom(prop))) {
                        characteristics.add("函数性(Functional)");
                    }
                    if (reasoner.isEntailed(df.getOWLInverseFunctionalObjectPropertyAxiom(prop))) {
                        characteristics.add("反函数性(InverseFunctional)");
                    }
                    if (reasoner.isEntailed(df.getOWLTransitiveObjectPropertyAxiom(prop))) {
                        characteristics.add("传递性(Transitive)");
                    }
                    if (reasoner.isEntailed(df.getOWLSymmetricObjectPropertyAxiom(prop))) {
                        characteristics.add("对称性(Symmetric)");
                    }
                    if (reasoner.isEntailed(df.getOWLAsymmetricObjectPropertyAxiom(prop))) {
                        characteristics.add("非对称性(Asymmetric)");
                    }
                    if (reasoner.isEntailed(df.getOWLIrreflexiveObjectPropertyAxiom(prop))) {
                        characteristics.add("非自反性(Irreflexive)");
                    }
                    if (reasoner.isEntailed(df.getOWLReflexiveObjectPropertyAxiom(prop))) {
                        characteristics.add("自反性(Reflexive)");
                    }
                } catch (Exception e) {
                    log.debug("属性特征检测失败 ({}): {}", propName, e.getMessage());
                }

                if (!characteristics.isEmpty()) {
                    propertyCharacteristics.put(propName, characteristics);
                }

                // 检测逆属性
                try {
                    Node<OWLObjectPropertyExpression> inverseProps = reasoner.getInverseObjectProperties(prop);
                    List<String> inverseNames = inverseProps.entities()
                            .filter(p -> !p.isOWLBottomObjectProperty())
                            .map(p -> p instanceof OWLObjectProperty ? sfp.getShortForm((OWLObjectProperty) p) : p.toString())
                            .sorted()
                            .collect(Collectors.toList());
                    if (!inverseNames.isEmpty()) {
                        inverseProperties.put(propName, String.join(", ", inverseNames));
                    }
                } catch (Exception ignored) {}

                // 检测等价属性
                try {
                    Node<OWLObjectPropertyExpression> equivProps = reasoner.getEquivalentObjectProperties(prop);
                    List<String> equivNames = equivProps.entities()
                            .filter(p -> !p.equals(prop) && !p.isOWLTopObjectProperty() && !p.isOWLBottomObjectProperty())
                            .map(p -> p instanceof OWLObjectProperty ? sfp.getShortForm((OWLObjectProperty) p) : p.toString())
                            .sorted()
                            .collect(Collectors.toList());
                    if (!equivNames.isEmpty()) {
                        equivalentProperties.put(propName, equivNames);
                    }
                } catch (Exception ignored) {}
            }

            int totalClasses = (int) owlOntology.classesInSignature().count();
            int totalObjProps = (int) owlOntology.objectPropertiesInSignature().count();
            int totalDataProps = (int) owlOntology.dataPropertiesInSignature().count();
            int totalAxioms = owlOntology.getAxiomCount();

            // ==================== 能力5: 数据属性推导 ====================
            Map<String, List<String>> inferredDataPropertyHierarchy = new LinkedHashMap<>();
            Map<String, List<String>> dataPropertyDomains = new LinkedHashMap<>();
            for (OWLDataProperty dp : owlOntology.getDataPropertiesInSignature()) {
                if (dp.isOWLTopDataProperty() || dp.isOWLBottomDataProperty()) continue;
                String dpName = sfp.getShortForm(dp);
                // 推断子数据属性
                try {
                    NodeSet<OWLDataProperty> subProps = reasoner.getSubDataProperties(dp, false);
                    List<String> subNames = subProps.entities()
                            .filter(p -> !p.isOWLBottomDataProperty() && !p.equals(dp))
                            .map(p -> sfp.getShortForm(p))
                            .sorted()
                            .collect(Collectors.toList());
                    if (!subNames.isEmpty()) {
                        inferredDataPropertyHierarchy.put(dpName, subNames);
                    }
                } catch (Exception ignored) {}
                // 数据属性的定义域
                try {
                    NodeSet<OWLClass> domainNodes = reasoner.getDataPropertyDomains(dp, false);
                    List<String> domainNames = domainNodes.entities()
                            .filter(c -> !c.isOWLThing() && !c.isOWLNothing())
                            .map(c -> sfp.getShortForm(c))
                            .sorted()
                            .collect(Collectors.toList());
                    if (!domainNames.isEmpty()) {
                        dataPropertyDomains.put(dpName, domainNames);
                    }
                } catch (Exception ignored) {}
            }

            // ==================== 能力6: SWRL 规则推导 ====================
            List<String> swrlRuleResults = new ArrayList<>();
            try {
                // 检查本体中是否包含 SWRL 规则（swrl:Imp 公理）
                var swrlAxioms = owlOntology.getAxioms(org.semanticweb.owlapi.model.AxiomType.SWRL_RULE);
                if (!swrlAxioms.isEmpty()) {
                    for (var rule : swrlAxioms) {
                        String ruleStr = rule.toString();
                        swrlRuleResults.add("SWRL 规则: " + (ruleStr.length() > 200 ? ruleStr.substring(0, 200) + "..." : ruleStr));
                    }
                    log.info("HermiT 检测到 {} 条 SWRL 规则", swrlAxioms.size());
                } else {
                    swrlRuleResults.add("当前本体未定义 SWRL 规则（swrl:Imp），如需规则推理请在 TTL 中声明 SWRL 规则");
                }
            } catch (Exception e) {
                log.debug("SWRL 规则检测失败: {}", e.getMessage());
                swrlRuleResults.add("SWRL 规则检测不支持或失败: " + e.getMessage());
            }

            return HermiTCheckResult.builder()
                    .consistent(consistent)
                    .unsatisfiableClasses(unsatisfiableClasses)
                    .inconsistencies(inconsistencies)
                    .inferredHierarchy(inferredHierarchy)
                    .inferredPropertyHierarchy(inferredPropertyHierarchy)
                    .equivalentClasses(equivalentClasses)
                    .disjointClasses(disjointClasses)
                    .inferredSuperClasses(inferredSuperClasses)
                    .totalIndividuals(totalIndividuals)
                    .inferredIndividualTypes(inferredIndividualTypes)
                    .classInstances(classInstances)
                    .propertyCharacteristics(propertyCharacteristics)
                    .inverseProperties(inverseProperties)
                    .equivalentProperties(equivalentProperties)
                    .inferredDataPropertyHierarchy(inferredDataPropertyHierarchy)
                    .dataPropertyDomains(dataPropertyDomains)
                    .swrlRuleResults(swrlRuleResults)
                    .totalClasses(totalClasses)
                    .totalObjectProperties(totalObjProps)
                    .totalDatatypeProperties(totalDataProps)
                    .totalAxioms(totalAxioms)
                    .reasonerName("HermiT")
                    .reasonerVersion("1.3.8.500")
                    .preparationTimeMs(prepTime)
                    .build();

        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            log.error("HermiT 推理执行异常", e);
            throw new BusinessException("HermiT 推理失败: " + e.getMessage());
        }
    }

    // ==================== Pellet: OWL 2 DL 推理 ====================

    /**
     * 使用 Pellet 对 TTL 文件执行 OWL 2 DL 一致性检查
     */
    public HermiTCheckResult checkConsistencyWithPellet(String ttlPath) {
        try {
            log.info("使用 Pellet 执行 OWL 2 DL 一致性检查: {}", ttlPath);

            OWLOntologyManager manager = OWLManager.createOWLOntologyManager();
            OWLOntology ontology;

            if (ttlPath != null && Files.exists(Paths.get(ttlPath))) {
                ontology = manager.loadOntologyFromOntologyDocument(
                        IRI.create(Paths.get(ttlPath).toUri()));
            } else {
                throw new BusinessException("TTL 文件不存在: " + ttlPath);
            }

            normalizeDatatypes(ontology);

            return doPelletCheck(ontology);

        } catch (BusinessException e) {
            throw e;
        } catch (OWLOntologyCreationException e) {
            log.error("Pellet 推理失败: 无法加载本体", e);
            throw new BusinessException("Pellet 推理失败: 无法加载本体 - " + e.getMessage());
        } catch (Exception e) {
            log.error("Pellet 推理失败", e);
            throw new BusinessException("Pellet 推理失败: " + e.getMessage());
        }
    }

    /**
     * 使用 Pellet 对数据库中的本体数据执行 OWL 2 DL 一致性检查
     */
    public HermiTCheckResult checkConsistencyWithPellet(Ontology ontology, List<ObjectType> objectTypes, List<LinkType> linkTypes) {
        try {
            log.info("使用 Pellet 检查本体一致性: {} ({})", ontology.getName(), ontology.getId());

            OWLOntologyManager manager = OWLManager.createOWLOntologyManager();
            OWLDataFactory df = manager.getOWLDataFactory();

            String baseUri = "http://ontologybuilder.local/ontology/" + ontology.getId() + "#";
            IRI ontologyIri = IRI.create(baseUri.substring(0, baseUri.length() - 1));

            OWLOntology owlOntology = manager.createOntology(ontologyIri);

            // 添加本体注解
            if (ontology.getName() != null) {
                OWLAnnotation labelAnnotation = df.getOWLAnnotation(df.getRDFSLabel(),
                        df.getOWLLiteral(ontology.getName(), "zh"));
                manager.applyChange(new AddOntologyAnnotation(owlOntology, labelAnnotation));
            }
            if (ontology.getDescription() != null) {
                OWLAnnotation commentAnnotation = df.getOWLAnnotation(df.getRDFSComment(),
                        df.getOWLLiteral(ontology.getDescription(), "zh"));
                manager.applyChange(new AddOntologyAnnotation(owlOntology, commentAnnotation));
            }

            // 构建 IRI -> OWLClass 映射
            Map<String, OWLClass> classMap = new HashMap<>();
            for (ObjectType ot : objectTypes) {
                String iriStr = (ot.getIri() != null && !ot.getIri().isEmpty())
                        ? ot.getIri() : baseUri + "Class_" + ot.getId();
                OWLClass cls = df.getOWLClass(IRI.create(iriStr));
                classMap.put(iriStr, cls);
                classMap.put(ot.getId(), cls);

                manager.addAxiom(owlOntology, df.getOWLDeclarationAxiom(cls));
                if (ot.getLabel() != null) {
                    OWLAnnotationAssertionAxiom labelAxiom = df.getOWLAnnotationAssertionAxiom(
                            cls.getIRI(), df.getOWLAnnotation(df.getRDFSLabel(),
                                    df.getOWLLiteral(ot.getLabel(), "zh")));
                    manager.addAxiom(owlOntology, labelAxiom);
                }
            }

            // 添加子类关系
            for (ObjectType ot : objectTypes) {
                if (ot.getParentIri() != null && !ot.getParentIri().isEmpty()) {
                    OWLClass child = classMap.get(ot.getId());
                    OWLClass parent = classMap.get(ot.getParentIri());
                    if (child != null && parent != null) {
                        manager.addAxiom(owlOntology, df.getOWLSubClassOfAxiom(child, parent));
                    }
                }
            }

            // 添加对象属性（注意：importTtl 中 DatatypeProperty 也被存入 LinkType，
            // 需要过滤掉 range 为 XSD 数据类型的属性，避免同一个 IRI 被同时声明为
            // OWLObjectProperty 和 OWLDataProperty 导致 Pellet 推理不一致）
            Set<String> xsdDataPropIris = new HashSet<>();
            for (LinkType lt : linkTypes) {
                String rangeType = lt.getTargetType();
                // 如果 range 是 XSD 数据类型（如 xsd:string），则该属性实际上是数据属性
                if (rangeType != null && (rangeType.startsWith("http://www.w3.org/2001/XMLSchema#")
                        || rangeType.startsWith("http://www.w3.org/2000/10/XMLSchema#")
                        || rangeType.startsWith("http://www.w3.org/2001/XMLSchema#"))) {
                    xsdDataPropIris.add(lt.getIri());
                }
            }

            for (LinkType lt : linkTypes) {
                String propIriStr = (lt.getIri() != null && !lt.getIri().isEmpty())
                        ? lt.getIri() : baseUri + "Property_" + lt.getId();

                // 跳过数据属性（range 为 XSD 类型的属性由 propertiesJson 或 TTL 负责声明）
                if (xsdDataPropIris.contains(lt.getIri())) {
                    log.debug("跳过数据属性（range为XSD类型）: {} -> {}", propIriStr, lt.getTargetType());
                    continue;
                }

                OWLObjectProperty prop = df.getOWLObjectProperty(IRI.create(propIriStr));

                manager.addAxiom(owlOntology, df.getOWLDeclarationAxiom(prop));
                if (lt.getLabel() != null) {
                    OWLAnnotationAssertionAxiom labelAxiom = df.getOWLAnnotationAssertionAxiom(
                            prop.getIRI(), df.getOWLAnnotation(df.getRDFSLabel(),
                                    df.getOWLLiteral(lt.getLabel(), "zh")));
                    manager.addAxiom(owlOntology, labelAxiom);
                }

                if (lt.getSourceType() != null && !lt.getSourceType().isEmpty()) {
                    OWLClass domain = classMap.get(lt.getSourceType());
                    if (domain != null) {
                        manager.addAxiom(owlOntology, df.getOWLObjectPropertyDomainAxiom(prop, domain));
                    }
                }
                if (lt.getTargetType() != null && !lt.getTargetType().isEmpty()) {
                    OWLClass range = classMap.get(lt.getTargetType());
                    if (range != null) {
                        manager.addAxiom(owlOntology, df.getOWLObjectPropertyRangeAxiom(prop, range));
                    }
                }
            }

            // 添加数据属性（DatatypeProperty）从 ObjectType 的 propertiesJson 中提取
            com.fasterxml.jackson.databind.ObjectMapper jsonMapper = new com.fasterxml.jackson.databind.ObjectMapper();
            Set<String> dbDataPropIriSet = new HashSet<>();  // 记录从 DB 构建的数据属性 IRI，避免与 TTL 加载重复
            for (ObjectType ot : objectTypes) {
                if (ot.getPropertiesJson() != null && !ot.getPropertiesJson().isBlank()) {
                    try {
                        java.util.List<java.util.Map<String, String>> dataProps = jsonMapper.readValue(
                                ot.getPropertiesJson(), java.util.List.class);
                        OWLClass domainCls = classMap.get(ot.getIri() != null ? ot.getIri() : ot.getId());
                        for (java.util.Map<String, String> dp : dataProps) {
                            String dpIri = dp.getOrDefault("iri", baseUri + "dp_" + java.util.UUID.randomUUID().toString().substring(0, 8));
                            OWLDataProperty dataProp = df.getOWLDataProperty(IRI.create(dpIri));
                            manager.addAxiom(owlOntology, df.getOWLDeclarationAxiom(dataProp));
                            dbDataPropIriSet.add(dpIri);
                            String dpLabel = dp.get("label");
                            if (dpLabel != null && !dpLabel.isBlank()) {
                                OWLAnnotationAssertionAxiom labelAxiom = df.getOWLAnnotationAssertionAxiom(
                                        dataProp.getIRI(), df.getOWLAnnotation(df.getRDFSLabel(),
                                                df.getOWLLiteral(dpLabel, "zh")));
                                manager.addAxiom(owlOntology, labelAxiom);
                            }
                            if (domainCls != null) {
                                manager.addAxiom(owlOntology, df.getOWLDataPropertyDomainAxiom(dataProp, domainCls));
                            }
                            String dpRange = expandXsdTypeUri(dp.get("range"));
                            if (dpRange != null && !dpRange.isBlank()) {
                                OWLDatatype datatype = df.getOWLDatatype(IRI.create(dpRange));
                                manager.addAxiom(owlOntology, df.getOWLDataPropertyRangeAxiom(dataProp, datatype));
                            } else {
                                manager.addAxiom(owlOntology, df.getOWLDataPropertyRangeAxiom(dataProp, df.getStringOWLDatatype()));
                            }
                        }
                    } catch (Exception e) {
                        log.warn("解析 ObjectType {} 的 propertiesJson 失败: {}", ot.getId(), e.getMessage());
                    }
                }
            }

            // 尝试从 TTL 文件中加载个体（Named Individuals）和数据属性声明以支持实例推理
            if (ontology.getTtlPath() != null && Files.exists(Paths.get(ontology.getTtlPath()))) {
                try {
                    OWLOntologyManager ttlManager = OWLManager.createOWLOntologyManager();
                    OWLOntology ttlOntology = ttlManager.loadOntologyFromOntologyDocument(
                            IRI.create(Paths.get(ontology.getTtlPath()).toUri()));
                    int individualCount = 0;
                    int dpCount = 0;
                    for (OWLAxiom axiom : ttlOntology.getAxioms()) {
                        if (axiom instanceof OWLDeclarationAxiom) {
                            OWLDeclarationAxiom decl = (OWLDeclarationAxiom) axiom;
                            if (decl.getEntity() instanceof OWLNamedIndividual) {
                                manager.addAxiom(owlOntology, decl);
                                individualCount++;
                            } else if (decl.getEntity() instanceof OWLDataProperty) {
                                // 跳过已从 propertiesJson 构建的数据属性声明，避免重复
                                String propIri = decl.getEntity().getIRI().toString();
                                if (!dbDataPropIriSet.contains(propIri)) {
                                    manager.addAxiom(owlOntology, decl);
                                    dpCount++;
                                }
                            }
                        } else if (axiom instanceof OWLClassAssertionAxiom
                                || axiom instanceof OWLObjectPropertyAssertionAxiom
                                || axiom instanceof OWLDataPropertyAssertionAxiom
                                || axiom instanceof OWLDataPropertyDomainAxiom) {
                            manager.addAxiom(owlOntology, axiom);
                        } else if (axiom instanceof OWLDataPropertyRangeAxiom) {
                            // 跳过已从 propertiesJson 构建的 range 声明，避免重复或冲突
                            OWLDataPropertyRangeAxiom rangeAxiom = (OWLDataPropertyRangeAxiom) axiom;
                            String propIri = rangeAxiom.getProperty().asOWLDataProperty().getIRI().toString();
                            if (!dbDataPropIriSet.contains(propIri)) {
                                manager.addAxiom(owlOntology, axiom);
                            }
                        } else if (axiom instanceof OWLSubDataPropertyOfAxiom) {
                            manager.addAxiom(owlOntology, axiom);
                        }
                    }
                    if (individualCount > 0 || dpCount > 0) {
                        log.info("从 TTL 文件加载了 {} 个个体和 {} 个数据属性声明到本体中，用于 Pellet 推理", individualCount, dpCount);
                    }
                } catch (Exception e) {
                    log.warn("加载 TTL 文件中的个体和数据属性失败，继续使用 DB 数据进行推理: {}", e.getMessage());
                }
            }

            normalizeDatatypes(owlOntology);

            return doPelletCheck(owlOntology);

        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            log.error("Pellet 推理失败", e);
            throw new BusinessException("Pellet 推理失败: " + e.getMessage());
        }
    }

    /**
     * 核心推理逻辑：使用 Pellet 对 OWL API 本体执行一致性检查
     */
    private HermiTCheckResult doPelletCheck(OWLOntology owlOntology) {
        try {
            OWLReasonerFactory pelletFactory = new PelletReasonerFactory();
            OWLReasoner reasoner = pelletFactory.createReasoner(owlOntology);
            OWLDataFactory df = owlOntology.getOWLOntologyManager().getOWLDataFactory();

            long startPrep = System.currentTimeMillis();
            reasoner.precomputeInferences(InferenceType.CLASS_HIERARCHY,
                    InferenceType.OBJECT_PROPERTY_HIERARCHY,
                    InferenceType.DATA_PROPERTY_HIERARCHY);
            long prepTime = System.currentTimeMillis() - startPrep;
            log.info("Pellet 预计算完成, 耗时: {}ms", prepTime);

            boolean consistent = reasoner.isConsistent();
            log.info("Pellet 一致性检查结果: {}", consistent);

            List<String> inconsistencies = new ArrayList<>();
            List<String> unsatisfiableClasses = new ArrayList<>();
            Map<String, List<String>> inferredHierarchy = new LinkedHashMap<>();
            Map<String, List<String>> inferredPropertyHierarchy = new LinkedHashMap<>();

            if (!consistent) {
                inconsistencies.add("本体不一致: 存在逻辑矛盾，请检查类定义、属性约束或个体声明");

                try {
                    for (OWLClass cls : owlOntology.getClassesInSignature()) {
                        if (!reasoner.isSatisfiable(cls)) {
                            String name = new SimpleShortFormProvider().getShortForm(cls);
                            unsatisfiableClasses.add(name + " (" + cls.getIRI() + ")");
                        }
                    }
                } catch (Exception ignored) {}
                logDatatypeMismatchDiagnostics(owlOntology);
            }

            // 检查不可满足类
            if (consistent) {
                ShortFormProvider sfp = new SimpleShortFormProvider();
                for (OWLClass cls : owlOntology.getClassesInSignature()) {
                    if (cls.isOWLThing() || cls.isOWLNothing()) continue;
                    if (!reasoner.isSatisfiable(cls)) {
                        String name = sfp.getShortForm(cls);
                        unsatisfiableClasses.add(name + " (" + cls.getIRI() + ")");
                        inconsistencies.add("不可满足类: " + name + " 被推断为等同于 owl:Nothing");
                    }
                }
            }

            // 推断类层次结构
            ShortFormProvider sfp = new SimpleShortFormProvider();
            for (OWLClass cls : owlOntology.getClassesInSignature()) {
                if (cls.isOWLThing() || cls.isOWLNothing()) continue;
                NodeSet<OWLClass> subClasses = reasoner.getSubClasses(cls, false);
                List<String> subClassNames = subClasses.entities()
                        .filter(c -> !c.isOWLNothing())
                        .map(c -> sfp.getShortForm(c))
                        .sorted()
                        .collect(Collectors.toList());
                if (!subClassNames.isEmpty()) {
                    inferredHierarchy.put(sfp.getShortForm(cls), subClassNames);
                }
            }

            // 推断对象属性层次
            for (OWLObjectProperty prop : owlOntology.getObjectPropertiesInSignature()) {
                if (prop.isOWLTopObjectProperty() || prop.isOWLBottomObjectProperty()) continue;
                NodeSet<OWLObjectPropertyExpression> subProps = reasoner.getSubObjectProperties(prop, false);
                List<String> subPropNames = subProps.entities()
                        .filter(p -> !p.isOWLBottomObjectProperty())
                        .map(p -> p instanceof OWLObjectProperty ? sfp.getShortForm((OWLObjectProperty) p) : p.toString())
                        .sorted()
                        .collect(Collectors.toList());
                if (!subPropNames.isEmpty()) {
                    inferredPropertyHierarchy.put(sfp.getShortForm(prop), subPropNames);
                }
            }

            // 等价类检测
            Map<String, List<String>> equivalentClasses = new LinkedHashMap<>();
            for (OWLClass cls : owlOntology.getClassesInSignature()) {
                if (cls.isOWLThing() || cls.isOWLNothing()) continue;
                try {
                    Node<OWLClass> equiv = reasoner.getEquivalentClasses(cls);
                    List<String> equivNames = equiv.entities()
                            .filter(c -> !c.equals(cls) && !c.isOWLThing() && !c.isOWLNothing())
                            .map(c -> sfp.getShortForm(c))
                            .sorted()
                            .collect(Collectors.toList());
                    if (!equivNames.isEmpty()) {
                        equivalentClasses.put(sfp.getShortForm(cls), equivNames);
                    }
                } catch (Exception ignored) {}
            }

            // 互斥类检测
            Map<String, List<String>> disjointClasses = new LinkedHashMap<>();
            for (OWLClass cls : owlOntology.getClassesInSignature()) {
                if (cls.isOWLThing() || cls.isOWLNothing()) continue;
                try {
                    NodeSet<OWLClass> disjoint = reasoner.getDisjointClasses(cls);
                    List<String> disjointNames = disjoint.entities()
                            .filter(c -> !c.isOWLNothing())
                            .map(c -> sfp.getShortForm(c))
                            .sorted()
                            .collect(Collectors.toList());
                    if (!disjointNames.isEmpty()) {
                        disjointClasses.put(sfp.getShortForm(cls), disjointNames);
                    }
                } catch (Exception ignored) {}
            }

            // 互斥冲突检测
            if (!consistent && !unsatisfiableClasses.isEmpty()) {
                for (OWLClass cls : owlOntology.getClassesInSignature()) {
                    if (cls.isOWLThing() || cls.isOWLNothing()) continue;
                    if (!reasoner.isSatisfiable(cls)) {
                        try {
                            NodeSet<OWLClass> disjoint = reasoner.getDisjointClasses(cls);
                            for (OWLClass dc : disjoint.entities().collect(Collectors.toList())) {
                                if (!dc.isOWLNothing()) {
                                    inconsistencies.add("互斥冲突: " + sfp.getShortForm(cls)
                                            + " 与 " + sfp.getShortForm(dc) + " 声明互斥，但存在个体同时属于两者");
                                }
                            }
                        } catch (Exception ignored) {}
                    }
                }
            }

            // 推断超类
            Map<String, List<String>> inferredSuperClasses = new LinkedHashMap<>();
            for (OWLClass cls : owlOntology.getClassesInSignature()) {
                if (cls.isOWLThing() || cls.isOWLNothing()) continue;
                try {
                    NodeSet<OWLClass> superClasses = reasoner.getSuperClasses(cls, false);
                    List<String> superClassNames = superClasses.entities()
                            .filter(c -> !c.isOWLThing())
                            .map(c -> sfp.getShortForm(c))
                            .sorted()
                            .collect(Collectors.toList());
                    if (!superClassNames.isEmpty()) {
                        inferredSuperClasses.put(sfp.getShortForm(cls), superClassNames);
                    }
                } catch (Exception ignored) {}
            }

            // 实例归属判断
            Map<String, List<String>> inferredIndividualTypes = new LinkedHashMap<>();
            Map<String, List<String>> classInstances = new LinkedHashMap<>();
            int totalIndividuals = 0;

            try {
                for (OWLNamedIndividual individual : owlOntology.getIndividualsInSignature()) {
                    totalIndividuals++;
                    try {
                        NodeSet<OWLClass> types = reasoner.getTypes(individual, false);
                        List<String> typeNames = types.entities()
                                .filter(c -> !c.isOWLThing())
                                .map(c -> sfp.getShortForm(c))
                                .sorted()
                                .collect(Collectors.toList());
                        if (!typeNames.isEmpty()) {
                            inferredIndividualTypes.put(sfp.getShortForm(individual), typeNames);
                        }
                    } catch (Exception ignored) {}
                }

                for (OWLClass cls : owlOntology.getClassesInSignature()) {
                    if (cls.isOWLThing() || cls.isOWLNothing()) continue;
                    try {
                        NodeSet<OWLNamedIndividual> instances = reasoner.getInstances(cls, false);
                        List<String> instanceNames = instances.entities()
                                .map(i -> sfp.getShortForm(i))
                                .sorted()
                                .collect(Collectors.toList());
                        if (!instanceNames.isEmpty()) {
                            classInstances.put(sfp.getShortForm(cls), instanceNames);
                        }
                    } catch (Exception ignored) {}
                }
            } catch (Exception e) {
                log.warn("Pellet 实例推理部分失败: {}", e.getMessage());
            }

            // 属性与关系推导
            Map<String, List<String>> propertyCharacteristics = new LinkedHashMap<>();
            Map<String, String> inverseProperties = new LinkedHashMap<>();
            Map<String, List<String>> equivalentProperties = new LinkedHashMap<>();

            for (OWLObjectProperty prop : owlOntology.getObjectPropertiesInSignature()) {
                if (prop.isOWLTopObjectProperty() || prop.isOWLBottomObjectProperty()) continue;
                String propName = sfp.getShortForm(prop);
                List<String> characteristics = new ArrayList<>();

                try {
                    if (reasoner.isEntailed(df.getOWLFunctionalObjectPropertyAxiom(prop))) {
                        characteristics.add("函数性(Functional)");
                    }
                    if (reasoner.isEntailed(df.getOWLInverseFunctionalObjectPropertyAxiom(prop))) {
                        characteristics.add("反函数性(InverseFunctional)");
                    }
                    if (reasoner.isEntailed(df.getOWLTransitiveObjectPropertyAxiom(prop))) {
                        characteristics.add("传递性(Transitive)");
                    }
                    if (reasoner.isEntailed(df.getOWLSymmetricObjectPropertyAxiom(prop))) {
                        characteristics.add("对称性(Symmetric)");
                    }
                    if (reasoner.isEntailed(df.getOWLAsymmetricObjectPropertyAxiom(prop))) {
                        characteristics.add("非对称性(Asymmetric)");
                    }
                    if (reasoner.isEntailed(df.getOWLIrreflexiveObjectPropertyAxiom(prop))) {
                        characteristics.add("非自反性(Irreflexive)");
                    }
                    if (reasoner.isEntailed(df.getOWLReflexiveObjectPropertyAxiom(prop))) {
                        characteristics.add("自反性(Reflexive)");
                    }
                } catch (Exception e) {
                    log.debug("Pellet 属性特征检测失败 ({}): {}", propName, e.getMessage());
                }

                if (!characteristics.isEmpty()) {
                    propertyCharacteristics.put(propName, characteristics);
                }

                // 逆属性
                try {
                    Node<OWLObjectPropertyExpression> inverseProps = reasoner.getInverseObjectProperties(prop);
                    List<String> inverseNames = inverseProps.entities()
                            .filter(p -> !p.isOWLBottomObjectProperty())
                            .map(p -> p instanceof OWLObjectProperty ? sfp.getShortForm((OWLObjectProperty) p) : p.toString())
                            .sorted()
                            .collect(Collectors.toList());
                    if (!inverseNames.isEmpty()) {
                        inverseProperties.put(propName, String.join(", ", inverseNames));
                    }
                } catch (Exception ignored) {}

                // 等价属性
                try {
                    Node<OWLObjectPropertyExpression> equivProps = reasoner.getEquivalentObjectProperties(prop);
                    List<String> equivNames = equivProps.entities()
                            .filter(p -> !p.equals(prop) && !p.isOWLTopObjectProperty() && !p.isOWLBottomObjectProperty())
                            .map(p -> p instanceof OWLObjectProperty ? sfp.getShortForm((OWLObjectProperty) p) : p.toString())
                            .sorted()
                            .collect(Collectors.toList());
                    if (!equivNames.isEmpty()) {
                        equivalentProperties.put(propName, equivNames);
                    }
                } catch (Exception ignored) {}
            }

            int totalClasses = (int) owlOntology.classesInSignature().count();
            int totalObjProps = (int) owlOntology.objectPropertiesInSignature().count();
            int totalDataProps = (int) owlOntology.dataPropertiesInSignature().count();
            int totalAxioms = owlOntology.getAxiomCount();

            // ==================== 能力5: 数据属性推导 ====================
            Map<String, List<String>> inferredDataPropertyHierarchy = new LinkedHashMap<>();
            Map<String, List<String>> dataPropertyDomains = new LinkedHashMap<>();
            for (OWLDataProperty dp : owlOntology.getDataPropertiesInSignature()) {
                if (dp.isOWLTopDataProperty() || dp.isOWLBottomDataProperty()) continue;
                String dpName = sfp.getShortForm(dp);
                // 推断子数据属性
                try {
                    NodeSet<OWLDataProperty> subProps = reasoner.getSubDataProperties(dp, false);
                    List<String> subNames = subProps.entities()
                            .filter(p -> !p.isOWLBottomDataProperty() && !p.equals(dp))
                            .map(p -> sfp.getShortForm(p))
                            .sorted()
                            .collect(Collectors.toList());
                    if (!subNames.isEmpty()) {
                        inferredDataPropertyHierarchy.put(dpName, subNames);
                    }
                } catch (Exception ignored) {}
                // 数据属性的定义域
                try {
                    NodeSet<OWLClass> domainNodes = reasoner.getDataPropertyDomains(dp, false);
                    List<String> domainNames = domainNodes.entities()
                            .filter(c -> !c.isOWLThing() && !c.isOWLNothing())
                            .map(c -> sfp.getShortForm(c))
                            .sorted()
                            .collect(Collectors.toList());
                    if (!domainNames.isEmpty()) {
                        dataPropertyDomains.put(dpName, domainNames);
                    }
                } catch (Exception ignored) {}
            }

            // ==================== 能力6: SWRL 规则推导 ====================
            List<String> swrlRuleResults = new ArrayList<>();
            try {
                // 检查本体中是否包含 SWRL 规则（swrl:Imp 公理）
                var swrlAxioms = owlOntology.getAxioms(org.semanticweb.owlapi.model.AxiomType.SWRL_RULE);
                if (!swrlAxioms.isEmpty()) {
                    for (var rule : swrlAxioms) {
                        String ruleStr = rule.toString();
                        swrlRuleResults.add("SWRL 规则: " + (ruleStr.length() > 200 ? ruleStr.substring(0, 200) + "..." : ruleStr));
                    }
                    log.info("Pellet 检测到 {} 条 SWRL 规则", swrlAxioms.size());
                } else {
                    swrlRuleResults.add("当前本体未定义 SWRL 规则（swrl:Imp），如需规则推理请在 TTL 中声明 SWRL 规则");
                }
            } catch (Exception e) {
                log.debug("SWRL 规则检测失败: {}", e.getMessage());
                swrlRuleResults.add("SWRL 规则检测不支持或失败: " + e.getMessage());
            }

            return HermiTCheckResult.builder()
                    .consistent(consistent)
                    .unsatisfiableClasses(unsatisfiableClasses)
                    .inconsistencies(inconsistencies)
                    .inferredHierarchy(inferredHierarchy)
                    .inferredPropertyHierarchy(inferredPropertyHierarchy)
                    .equivalentClasses(equivalentClasses)
                    .disjointClasses(disjointClasses)
                    .inferredSuperClasses(inferredSuperClasses)
                    .totalIndividuals(totalIndividuals)
                    .inferredIndividualTypes(inferredIndividualTypes)
                    .classInstances(classInstances)
                    .propertyCharacteristics(propertyCharacteristics)
                    .inverseProperties(inverseProperties)
                    .equivalentProperties(equivalentProperties)
                    .inferredDataPropertyHierarchy(inferredDataPropertyHierarchy)
                    .dataPropertyDomains(dataPropertyDomains)
                    .swrlRuleResults(swrlRuleResults)
                    .totalClasses(totalClasses)
                    .totalObjectProperties(totalObjProps)
                    .totalDatatypeProperties(totalDataProps)
                    .totalAxioms(totalAxioms)
                    .reasonerName("Pellet")
                    .reasonerVersion("2.6.5")
                    .preparationTimeMs(prepTime)
                    .build();

        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            log.error("Pellet 推理执行异常", e);
            throw new BusinessException("Pellet 推理失败: " + e.getMessage());
        }
    }

    /**
     * 对 OWLOntology 中的数据属性断言进行标准化，解决数值类型不匹配问题。
     * Turtle 语法中 0.4883 解析为 xsd:decimal、5 解析为 xsd:integer，
     * 但本体中属性 range 可能定义为 xsd:double/xsd:float 等其他数值类型。
     * 覆盖 integer/decimal/double/float 及所有整数子类型的交叉转换。
     * Pellet 会严格校验数据类型并拒绝推理不一致本体，因此需要在推理前归一化。
     */
    private void normalizeDatatypes(OWLOntology ontology) {
        normalizeDatatypes(ontology, null);
    }

    /**
     * 标准化数据类型，支持 logSink 回调输出到 UI。
     * <p>
     * 处理以下情况以避免 Pellet 因数据类型不匹配/值格式违规而判定本体不一致：
     * <ol>
     *   <li>实例值是字符串但属性声明为数值类型 → 尝试转数值，失败则移除断言</li>
     *   <li>实例值有具体类型但属性声明为 string → 转字符串</li>
     *   <li>数值类型间不匹配 → 交叉转换</li>
     *   <li>类型匹配但字面值违反数据类型词法约束 → 移除断言（Pellet 严格校验）</li>
     * </ol>
     */
    private void normalizeDatatypes(OWLOntology ontology, BiConsumer<String, String> logSink) {
        OWLOntologyManager manager = ontology.getOWLOntologyManager();
        OWLDataFactory df = manager.getOWLDataFactory();
        String xsdNs = "http://www.w3.org/2001/XMLSchema#";

        Map<OWLDataProperty, OWLDatatype> propertyRanges = new HashMap<>();
        for (OWLDataPropertyRangeAxiom axiom : ontology.getAxioms(AxiomType.DATA_PROPERTY_RANGE)) {
            if (axiom.getRange() instanceof OWLDatatype) {
                propertyRanges.put(axiom.getProperty().asOWLDataProperty(), (OWLDatatype) axiom.getRange());
            }
        }

        if (propertyRanges.isEmpty()) return;

        int stringToNumeric = 0;
        int typedToString = 0;
        int numericMismatch = 0;
        int invalidRemoved = 0;
        int valueViolationRemoved = 0;
        int dateTimeFixed = 0;
        int dateFixed = 0;
        List<OWLOntologyChange> changes = new ArrayList<>();
        OWLDatatype xsdStringType = df.getOWLDatatype(xsdNs + "string");
        OWLDatatype xsdDateTimeType = df.getOWLDatatype(xsdNs + "dateTime");
        OWLDatatype xsdDateType = df.getOWLDatatype(xsdNs + "date");

        for (OWLDataPropertyAssertionAxiom axiom : ontology.getAxioms(AxiomType.DATA_PROPERTY_ASSERTION)) {
            OWLLiteral literal = axiom.getObject();
            OWLDatatype literalType = literal.getDatatype();
            OWLDatatype expectedType = propertyRanges.get(axiom.getProperty().asOWLDataProperty());

            if (expectedType == null || literalType == null) continue;

            // 情况0：类型匹配但字面值违反数据类型词法约束 → 尝试自动修复或移除断言
            // Pellet 会严格校验值内容，即使类型名匹配也会因值无效而判定不一致
            if (literalType.equals(expectedType)) {
                String violation = checkLiteralValueValid(literal.getLiteral(), expectedType);
                if (violation != null) {
                    // 尝试自动修复常见格式问题
                    OWLLiteral corrected = tryAutoCorrectLiteral(df, literal, literalType,
                            xsdDateTimeType, xsdDateType);
                    if (corrected != null) {
                        OWLDataPropertyAssertionAxiom newAxiom = df.getOWLDataPropertyAssertionAxiom(
                                axiom.getProperty(), axiom.getSubject(), corrected);
                        changes.add(new RemoveAxiom(ontology, axiom));
                        changes.add(new AddAxiom(ontology, newAxiom));
                        if (literalType.equals(xsdDateTimeType)) dateTimeFixed++;
                        else if (literalType.equals(xsdDateType)) dateFixed++;
                    } else {
                        changes.add(new RemoveAxiom(ontology, axiom));
                        valueViolationRemoved++;
                    }
                }
                continue;
            }

            String litUri = literalType.getIRI().toString();
            String expUri = expectedType.getIRI().toString();
            boolean litIsString = litUri.equals(xsdNs + "string");
            boolean expIsString = expUri.equals(xsdNs + "string");

            OWLLiteral newLiteral = null;
            boolean removeAxiom = false;

            // 情况1：实例值是纯字符串，但属性声明为数值类型 → 尝试转数值
            if (litIsString && !expIsString && isXsdNumericType(expUri)) {
                try {
                    newLiteral = df.getOWLLiteral(literal.getLiteral(), expectedType);
                    stringToNumeric++;
                } catch (Exception ignored) {
                    // 无法转换（如"南非"→xsd:decimal）→ 移除该断言，避免Pellet报本体不一致
                    removeAxiom = true;
                    invalidRemoved++;
                }
            }
            // 情况2：实例值有具体类型（如 date、integer），但属性声明为 string → 转字符串
            else if (expIsString && !litIsString) {
                try {
                    newLiteral = df.getOWLLiteral(literal.getLiteral(), xsdStringType);
                    typedToString++;
                } catch (Exception ignored) {}
            }
            // 情况3：数值类型间不匹配（integer↔decimal↔double↔float）
            else if (isXsdNumericMismatch(literalType, expectedType)) {
                try {
                    newLiteral = df.getOWLLiteral(literal.getLiteral(), expectedType);
                    numericMismatch++;
                } catch (Exception ignored) {}
            }

            if (removeAxiom) {
                changes.add(new RemoveAxiom(ontology, axiom));
            } else if (newLiteral != null) {
                OWLDataPropertyAssertionAxiom newAxiom = df.getOWLDataPropertyAssertionAxiom(
                        axiom.getProperty(), axiom.getSubject(), newLiteral);
                changes.add(new RemoveAxiom(ontology, axiom));
                changes.add(new AddAxiom(ontology, newAxiom));
            }
        }

        if (!changes.isEmpty()) {
            manager.applyChanges(changes);
            String summary = String.format(
                    "数据类型标准化: string→数值=%d, 有类型→string=%d, 数值交叉转换=%d, 无效断言移除=%d, "
                            + "值违规移除=%d, dateTime修正=%d, date修正=%d",
                    stringToNumeric, typedToString, numericMismatch, invalidRemoved,
                    valueViolationRemoved, dateTimeFixed, dateFixed);
            log.info(summary);
            int total = stringToNumeric + typedToString + numericMismatch + invalidRemoved
                    + valueViolationRemoved + dateTimeFixed + dateFixed;
            if (logSink != null && total > 0) {
                logSink.accept("info", summary);
            }
        }
    }

    /**
     * 对模型中的数值字面量做精度规范化：
     * 1. 消除 product()/sum() 等 Jena builtin 产生的浮点误差
     *    - 近整数值如 55.00000000000001 → 55 (xsd:integer)
     *    - 非整数噪声如 35.300000000000004 → 35.3 (xsd:double)
     * 2. 统一类型：整数等价数值统一转为 xsd:integer，消除同属性类型混用
     * <p>
     * 判断标准：分析词法形式的十进制位数是否远超该值所需的最小精度。
     * 仅靠 double 数值比较无法区分"干净 35.3"和"噪声 35.300000000000004"
     * （两者在 IEEE 754 中可能是同一 double），必须结合词法形式。
     */
    private void normalizeNumericPrecision(Model model) {
        final double EPSILON = 1e-9;
        final int MAX_DECIMALS = 10;
        List<Statement> oldStmts = new ArrayList<>();
        List<Statement> newStmts = new ArrayList<>();

        for (Statement stmt : model.listStatements().toList()) {
            RDFNode obj = stmt.getObject();
            if (!obj.isLiteral()) continue;
            Literal lit = obj.asLiteral();
            Object val = lit.getValue();
            if (!(val instanceof Number)) continue;

            double d = ((Number) val).doubleValue();

            // 找到能无损表示此值的最小十进制位数 N（0=整数, 1..MAX=小数）
            int bestN = -1;
            for (int n = 0; n <= MAX_DECIMALS; n++) {
                double factor = Math.pow(10, n);
                double candidate = Math.round(d * factor) / factor;
                if (Math.abs(d - candidate) <= EPSILON) {
                    bestN = n;
                    break;
                }
            }
            if (bestN < 0) continue; // 无法清理（极少见）

            // 通过词法形式判断是否需要修正（避免被 IEEE 754 表示误差干扰）
            String lex = lit.getLexicalForm();
            int dotIdx = lex.indexOf('.');
            int lexDecimals = dotIdx >= 0 ? lex.length() - dotIdx - 1 : 0;
            boolean isInteger = (bestN == 0);

            boolean needsCorrection;
            if (isInteger) {
                // 整数：词法有小数点 或 类型非 xsd:integer → 需要修正
                needsCorrection = lexDecimals > 0
                        || !"http://www.w3.org/2001/XMLSchema#integer".equals(lit.getDatatypeURI());
            } else {
                // 非整数：词法小数位远超最佳精度（允许 +2 容差）→ 需要修正
                needsCorrection = lexDecimals > bestN + 2;
            }

            if (!needsCorrection) continue;

            double cleaned = Math.round(d * Math.pow(10, bestN)) / Math.pow(10, bestN);

            Literal corrected;
            if (isInteger) {
                corrected = ResourceFactory.createTypedLiteral(
                        String.valueOf((long) cleaned),
                        org.apache.jena.datatypes.xsd.XSDDatatype.XSDinteger);
            } else {
                corrected = ResourceFactory.createTypedLiteral(
                        String.valueOf(cleaned),
                        org.apache.jena.datatypes.xsd.XSDDatatype.XSDdouble);
            }
            oldStmts.add(stmt);
            newStmts.add(ResourceFactory.createStatement(
                    stmt.getSubject(), stmt.getPredicate(), corrected));
        }

        for (int i = 0; i < oldStmts.size(); i++) {
            Statement old = oldStmts.get(i);
            model.remove(old.getSubject(), old.getPredicate(), old.getObject());
            model.add(newStmts.get(i));
        }
        if (!oldStmts.isEmpty()) {
            log.info("[Jena-SWRL] 数值精度规范化: {} 条修正（消除浮点误差+统一integer类型）", oldStmts.size());
        }
    }

    /** 判断是否为 XSD 数值类型（包括整数子类型） */
    private boolean isXsdNumericType(String typeUri) {
        String xsdNs = "http://www.w3.org/2001/XMLSchema#";
        return typeUri.equals(xsdNs + "decimal")
                || typeUri.equals(xsdNs + "double") || typeUri.equals(xsdNs + "float")
                || typeUri.equals(xsdNs + "integer") || typeUri.equals(xsdNs + "int")
                || typeUri.equals(xsdNs + "long") || typeUri.equals(xsdNs + "short")
                || typeUri.equals(xsdNs + "byte") || typeUri.equals(xsdNs + "unsignedByte")
                || typeUri.equals(xsdNs + "nonNegativeInteger") || typeUri.equals(xsdNs + "nonPositiveInteger")
                || typeUri.equals(xsdNs + "positiveInteger") || typeUri.equals(xsdNs + "negativeInteger")
                || typeUri.equals(xsdNs + "unsignedInt") || typeUri.equals(xsdNs + "unsignedLong")
                || typeUri.equals(xsdNs + "unsignedShort");
    }

    /**
     * 判断两个字面量数据类型是否属于可互换的 XSD 数值类型。
     * XSD 数值类型层级：
     *   decimal → integer → int/long/short/byte/nonNegativeInteger/positiveInteger/...
     *   double, float（独立于 decimal 分支）
     * 当实例字面量的类型与属性声明的 range 类型不同但属于同一数值体系时，
     * 可以进行无损或近似无损转换，避免 Pellet 因类型不匹配而判定本体不一致。
     */
    private boolean isXsdNumericMismatch(OWLDatatype literalType, OWLDatatype expectedType) {
        String litUri = literalType.getIRI().toString();
        String expUri = expectedType.getIRI().toString();
        String xsdNs = "http://www.w3.org/2001/XMLSchema#";

        // 整数及子类型：decimal/integer/int/long/short/byte/nonNegativeInteger/positiveInteger/...
        Set<String> xsdIntegerFamily = Set.of(
                xsdNs + "integer", xsdNs + "int", xsdNs + "long", xsdNs + "short",
                xsdNs + "byte", xsdNs + "unsignedByte",
                xsdNs + "nonNegativeInteger", xsdNs + "nonPositiveInteger",
                xsdNs + "positiveInteger", xsdNs + "negativeInteger",
                xsdNs + "unsignedInt", xsdNs + "unsignedLong", xsdNs + "unsignedShort");

        Set<String> xsdDecimalFamily = new java.util.HashSet<>(xsdIntegerFamily);
        xsdDecimalFamily.add(xsdNs + "decimal");

        Set<String> xsdFloatDouble = Set.of(xsdNs + "double", xsdNs + "float");

        boolean litIsNumeric = xsdDecimalFamily.contains(litUri) || xsdFloatDouble.contains(litUri);
        boolean expIsNumeric = xsdDecimalFamily.contains(expUri) || xsdFloatDouble.contains(expUri);

        return litIsNumeric && expIsNumeric && !litUri.equals(expUri);
    }

    /** @deprecated 由 {@link #isXsdNumericMismatch} 替代 */
    @Deprecated
    private boolean isXsdDecimalDoubleMismatch(OWLDatatype literalType, OWLDatatype expectedType) {
        return isXsdNumericMismatch(literalType, expectedType);
    }

    /**
     * 收集本体中所有不可满足的类（被 Pellet 推断为 owl:Nothing 子类的类）。
     * 用于诊断本体不一致的原因，帮助用户定位定义了矛盾约束的类。
     */
    private List<String> collectUnsatisfiableClasses(OWLOntology ontology, OWLReasoner reasoner) {
        List<String> unsatisfiable = new java.util.ArrayList<>();
        for (OWLClass cls : ontology.getClassesInSignature()) {
            if (!cls.isOWLThing() && !cls.isOWLNothing() && !reasoner.isSatisfiable(cls)) {
                unsatisfiable.add(cls.getIRI().getShortForm());
            }
        }
        return unsatisfiable;
    }

    /**
     * 收集数据类型不匹配诊断信息，返回诊断字符串列表（供 logSink 输出到 UI）。
     * @see #logDatatypeMismatchDiagnostics(OWLOntology)
     */
    private List<String> collectDatatypeMismatchDiagnostics(OWLOntology ontology) {
        List<String> result = new java.util.ArrayList<>();
        // 收集属性 range 声明
        Map<OWLDataProperty, OWLDatatype> propertyRanges = new HashMap<>();
        for (OWLDataPropertyRangeAxiom axiom : ontology.getAxioms(AxiomType.DATA_PROPERTY_RANGE)) {
            if (axiom.getRange() instanceof OWLDatatype) {
                propertyRanges.put(axiom.getProperty().asOWLDataProperty(), (OWLDatatype) axiom.getRange());
            }
        }
        if (propertyRanges.isEmpty()) return result;

        SimpleShortFormProvider sfp = new SimpleShortFormProvider();
        List<String> typeMismatches = new java.util.ArrayList<>();
        List<String> valueViolations = new java.util.ArrayList<>();

        for (OWLDataPropertyAssertionAxiom axiom : ontology.getAxioms(AxiomType.DATA_PROPERTY_ASSERTION)) {
            OWLLiteral literal = axiom.getObject();
            OWLDatatype literalType = literal.getDatatype();
            OWLDatatype expectedType = propertyRanges.get(axiom.getProperty().asOWLDataProperty());

            if (expectedType == null || literalType == null) continue;

            String propName = sfp.getShortForm(axiom.getProperty().asOWLDataProperty());
            String subjectName = sfp.getShortForm(axiom.getSubject().asOWLNamedIndividual());
            String valueStr = literal.getLiteral();
            String truncated = valueStr.length() > 80 ? valueStr.substring(0, 80) + "..." : valueStr;

            if (!literalType.equals(expectedType) && !isXsdNumericMismatch(literalType, expectedType)) {
                typeMismatches.add(String.format("数据类型名不匹配: %s = \"%s\"^^%s (属性 %s 声明为 %s)",
                        subjectName, truncated,
                        literalType.getIRI().getShortForm(),
                        propName, expectedType.getIRI().getShortForm()));
                continue;
            }

            String violation = checkLiteralValueValid(valueStr,
                    literalType.equals(expectedType) ? literalType : expectedType);
            if (violation != null) {
                valueViolations.add(String.format("字面值格式违规: %s = \"%s\"^^%s (%s)",
                        subjectName, truncated,
                        literalType.getIRI().getShortForm(), violation));
            }
        }

        if (!typeMismatches.isEmpty()) {
            result.add("数据类型名不匹配 (共 " + typeMismatches.size() + " 个，前 "
                    + Math.min(15, typeMismatches.size()) + " 个):");
            result.addAll(typeMismatches.subList(0, Math.min(15, typeMismatches.size())));
        }
        if (!valueViolations.isEmpty()) {
            result.add("字面值格式不满足数据类型约束 (共 " + valueViolations.size() + " 个，前 "
                    + Math.min(15, valueViolations.size()) + " 个)——这正是 Pellet 报不一致的直接原因:");
            result.addAll(valueViolations.subList(0, Math.min(15, valueViolations.size())));
        }
        return result;
    }

    /**
     * 诊断可能导致 Pellet 不一致的数据类型不匹配问题。
     * 找出所有字面值 datatype 与属性声明 range 不一致的数据属性断言，
     * 这些问题通常是 Pellet 判定本体不一致的主要原因。
     */
    private void logDatatypeMismatchDiagnostics(OWLOntology ontology) {
        // 收集属性 range 声明
        Map<OWLDataProperty, OWLDatatype> propertyRanges = new HashMap<>();
        for (OWLDataPropertyRangeAxiom axiom : ontology.getAxioms(AxiomType.DATA_PROPERTY_RANGE)) {
            if (axiom.getRange() instanceof OWLDatatype) {
                propertyRanges.put(axiom.getProperty().asOWLDataProperty(), (OWLDatatype) axiom.getRange());
            }
        }
        if (propertyRanges.isEmpty()) return;

        SimpleShortFormProvider sfp = new SimpleShortFormProvider();
        List<String> typeMismatches = new java.util.ArrayList<>();
        List<String> valueViolations = new java.util.ArrayList<>();

        for (OWLDataPropertyAssertionAxiom axiom : ontology.getAxioms(AxiomType.DATA_PROPERTY_ASSERTION)) {
            OWLLiteral literal = axiom.getObject();
            OWLDatatype literalType = literal.getDatatype();
            OWLDatatype expectedType = propertyRanges.get(axiom.getProperty().asOWLDataProperty());

            if (expectedType == null || literalType == null) continue;

            String propName = sfp.getShortForm(axiom.getProperty().asOWLDataProperty());
            String subjectName = sfp.getShortForm(axiom.getSubject().asOWLNamedIndividual());
            String valueStr = literal.getLiteral();
            String truncated = valueStr.length() > 80 ? valueStr.substring(0, 80) + "..." : valueStr;

            // 检查1：类型名不匹配（非数值交叉情况）
            if (!literalType.equals(expectedType) && !isXsdNumericMismatch(literalType, expectedType)) {
                typeMismatches.add(String.format("  %s = \"%s\"^^%s (属性 %s 声明为 %s)",
                        subjectName, truncated,
                        literalType.getIRI().getShortForm(),
                        propName, expectedType.getIRI().getShortForm()));
                continue;
            }

            // 检查2：类型名匹配但值内容不满足数据类型的词法约束
            // 这是 Pellet "The literal value does not satisfy the datatype restriction" 的主要原因
            String violation = checkLiteralValueValid(valueStr,
                    literalType.equals(expectedType) ? literalType : expectedType);
            if (violation != null) {
                valueViolations.add(String.format("  %s = \"%s\"^^%s (%s)",
                        subjectName, truncated,
                        literalType.getIRI().getShortForm(), violation));
            }
        }

        if (!typeMismatches.isEmpty()) {
            int showCount = Math.min(15, typeMismatches.size());
            log.warn("[Pellet] 数据类型名不匹配 (共 {} 个，以下展示前 {} 个):\n{}",
                    typeMismatches.size(), showCount,
                    String.join("\n", typeMismatches.subList(0, showCount)));
        }
        if (!valueViolations.isEmpty()) {
            int showCount = Math.min(15, valueViolations.size());
            log.warn("[Pellet] 字面值格式不满足数据类型约束 (共 {} 个，以下展示前 {} 个)——这正是 Pellet 报不一致的直接原因:\n{}",
                    valueViolations.size(), showCount,
                    String.join("\n", valueViolations.subList(0, showCount)));
        }
    }

    /**
     * 检查字面值字符串是否满足目标 XSD 数据类型的词法约束。
     * @return 违规描述，如果值合法则返回 null
     */
    private String checkLiteralValueValid(String value, OWLDatatype datatype) {
        String iri = datatype.getIRI().toString();
        String xsdNs = "http://www.w3.org/2001/XMLSchema#";
        try {
            if (iri.equals(xsdNs + "decimal") || iri.equals(xsdNs + "double")
                    || iri.equals(xsdNs + "float")) {
                // 拒绝含百分号、货币符号、中文、单位的"数字"
                if (value.contains("%") || value.contains("￥") || value.contains("$")
                        || value.contains("元") || value.contains("万") || value.contains("亿")) {
                    return "数值类型不允许包含特殊符号/单位/中文";
                }
                new java.math.BigDecimal(value);
                return null;
            }
            if (iri.equals(xsdNs + "integer") || iri.equals(xsdNs + "int")
                    || iri.equals(xsdNs + "long") || iri.equals(xsdNs + "short")
                    || iri.equals(xsdNs + "byte") || iri.equals(xsdNs + "nonNegativeInteger")
                    || iri.equals(xsdNs + "positiveInteger") || iri.equals(xsdNs + "negativeInteger")
                    || iri.equals(xsdNs + "nonPositiveInteger")
                    || iri.equals(xsdNs + "unsignedInt") || iri.equals(xsdNs + "unsignedLong")
                    || iri.equals(xsdNs + "unsignedShort") || iri.equals(xsdNs + "unsignedByte")) {
                if (value.contains("%") || value.contains("￥") || value.contains("$")
                        || value.contains("元") || value.contains("万") || value.contains("亿")
                        || value.contains(".")) {
                    return "整数类型不允许包含特殊符号/单位/小数";
                }
                new java.math.BigInteger(value);
                return null;
            }
            if (iri.equals(xsdNs + "boolean")) {
                if (!value.equals("true") && !value.equals("false")) {
                    return "布尔值只能为 true 或 false";
                }
                return null;
            }
            if (iri.equals(xsdNs + "date")) {
                java.time.LocalDate.parse(value);
                return null;
            }
            if (iri.equals(xsdNs + "dateTime")) {
                java.time.OffsetDateTime.parse(value);
                return null;
            }
            if (iri.equals(xsdNs + "time")) {
                java.time.LocalTime.parse(value);
                return null;
            }
        } catch (Exception e) {
            return "解析失败: " + e.getMessage();
        }
        return null;
    }

    /**
     * 尝试自动修正常见字面值格式问题，避免因格式不规范而移除有效数据。
     * 当前支持的修正：
     * <ul>
     *   <li>xsd:dateTime 值用空格代替 T 分隔符（如 "2026-04-15 10:00:00"→"2026-04-15T10:00:00"）</li>
     *   <li>xsd:date 值包含时间部分（如 "2026-04-15T00:00:00"→"2026-04-15"）</li>
     * </ul>
     *
     * @return 修正后的字面值，如果不支持自动修正则返回 null
     */
    private OWLLiteral tryAutoCorrectLiteral(OWLDataFactory df, OWLLiteral original,
                                              OWLDatatype literalType,
                                              OWLDatatype xsdDateTimeType,
                                              OWLDatatype xsdDateType) {
        String value = original.getLiteral();
        String xsdNs = "http://www.w3.org/2001/XMLSchema#";

        // xsd:dateTime：尝试将空格分隔符替换为 ISO 8601 标准的 T 分隔符
        if (literalType.equals(xsdDateTimeType)) {
            // 匹配 "YYYY-MM-DD HH:MM:SS" 格式（常见数据库 datetime 输出）
            if (value.matches("\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}")) {
                String corrected = value.replace(' ', 'T');
                if (isValidDateTimeLiteral(corrected)) {
                    return df.getOWLLiteral(corrected, xsdDateTimeType);
                }
            }
            // 无时区偏移的 ISO dateTime 格式（无空格，LocalDateTime 可解析）
            if (value.matches("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}")) {
                if (isValidDateTimeLiteral(value)) {
                    return df.getOWLLiteral(value, xsdDateTimeType);
                }
            }
        }

        // xsd:date：尝试从 dateTime 值中截取日期部分
        if (literalType.equals(xsdDateType)) {
            if (value.length() >= 10 && value.charAt(4) == '-' && value.charAt(7) == '-') {
                String datePart = value.substring(0, 10);
                try {
                    java.time.LocalDate.parse(datePart);
                    if (!datePart.equals(value)) {
                        return df.getOWLLiteral(datePart, xsdDateType);
                    }
                } catch (Exception ignored) {}
            }
        }

        return null;
    }

    /**
     * 判断 dateTime 字面量是否合法，兼容有/无时区偏移两种格式。
     */
    private boolean isValidDateTimeLiteral(String text) {
        try {
            java.time.OffsetDateTime.parse(text);
            return true;
        } catch (Exception e1) {
            try {
                java.time.LocalDateTime.parse(text);
                return true;
            } catch (Exception e2) {
                return false;
            }
        }
    }

    // ==================== Jena TDB2: 持久化存储 & SPARQL ====================

    /**
     * 加载 TTL 文件到 TDB2 持久化存储（覆盖已有数据）
     */
    public void loadTtlFile(String ttlPath) {
        try {
            String ontologyId = Paths.get(ttlPath).getFileName().toString().replace(".ttl", "");
            loadTtlFile(ttlPath, ontologyId);
        } catch (Exception e) {
            throw new BusinessException("加载 TTL 文件失败: " + e.getMessage());
        }
    }

    /**
     * 加载 TTL 文件到 TDB2，指定 ontologyId
     */
    public void loadTtlFile(String ttlPath, String ontologyId) {
        try {
            // 校验 TTL 文件格式
            String content = Files.readString(Paths.get(ttlPath), StandardCharsets.UTF_8);
            String trimmed = content.trim();
            if (trimmed.isEmpty()) {
                throw new BusinessException("TTL 文件为空");
            }
            if (trimmed.startsWith("{") || trimmed.startsWith("[")) {
                throw new BusinessException("TTL 文件内容为 JSON 格式，非 Turtle 格式。请重新提取本体，确保 ttlContent 字段正确生成");
            }
            if (trimmed.startsWith("```")) {
                throw new BusinessException("TTL 文件包含 Markdown 代码块标记，请修正 TTL 内容");
            }

            // 先修复TTL语法（中文IRI、SWRL语法等），确保OWL API可正确解析
            content = TtlSyntaxFixer.fixAll(content);

            // 先读取 TTL 到临时内存模型
            Model tempModel = ModelFactory.createDefaultModel();
            tempModel.read(new StringReader(content), null, "TURTLE");

            // 写入 TDB2 命名模型（使用 Dataset 级事务）
            tdb2Dataset.begin(ReadWrite.WRITE);
            try {
                Model namedModel = tdb2Dataset.getNamedModel(ontologyId);
                namedModel.removeAll();
                namedModel.add(tempModel);
                log.info("TTL 文件已加载到 TDB2: ontologyId={}, 三元组数量: {}", ontologyId, namedModel.size());
                tdb2Dataset.commit();
            } finally {
                tdb2Dataset.end();
            }

            // 启动内嵌 Fuseki Server（共享同一个 TDB2 Dataset）
            if (fusekiEmbeddedEnabled) {
                startEmbeddedFuseki();
            }

            // 加载完成后触发推理物化（先清除旧标记，确保 Jena SWRL 也重新执行）
            clearAndRematerialize(ontologyId);
        } catch (Exception e) {
            throw new BusinessException("加载 TTL 文件到 TDB2 失败: " + e.getMessage());
        }
    }

    /**
     * 从 TTL 内容加载到 TDB2
     */
    public void loadTtlContent(String ttlContent, String ontologyId) {
        try {
            // 先修复TTL语法（中文IRI、SWRL语法等），确保OWL API可正确解析
            String fixedContent = TtlSyntaxFixer.fixAll(ttlContent);

            // 先读取到临时内存模型
            Model tempModel = ModelFactory.createDefaultModel();
            tempModel.read(new StringReader(fixedContent), null, "TURTLE");

            // 诊断：检查SWRL规则结构完整性
            long swrlImpCount = tempModel.listStatements(null, RDF.type, tempModel.createResource("http://www.w3.org/2003/11/swrl#Imp")).toList().size();
            long swrlBodyCount = tempModel.listStatements(null, tempModel.createProperty("http://www.w3.org/2003/11/swrl#body"), (RDFNode)null).toList().size();
            long swrlHeadCount = tempModel.listStatements(null, tempModel.createProperty("http://www.w3.org/2003/11/swrl#head"), (RDFNode)null).toList().size();
            long classAtomCount = tempModel.listStatements(null, RDF.type, tempModel.createResource("http://www.w3.org/2003/11/swrl#ClassAtom")).toList().size();
            long dataPropAtomCount = tempModel.listStatements(null, RDF.type, tempModel.createResource("http://www.w3.org/2003/11/swrl#DatavaluedPropertyAtom")).toList().size();
            long indPropAtomCount = tempModel.listStatements(null, RDF.type, tempModel.createResource("http://www.w3.org/2003/11/swrl#IndividualPropertyAtom")).toList().size();
            long builtinAtomCount = tempModel.listStatements(null, RDF.type, tempModel.createResource("http://www.w3.org/2003/11/swrl#BuiltinAtom")).toList().size();
            long namedIndCount = tempModel.listStatements(null, RDF.type, tempModel.createResource("http://www.w3.org/2002/07/owl#NamedIndividual")).toList().size();
            // 诊断：验证 RDF List 结构 — 检查 rdf:first/rdf:rest 链是否完整
            long rdfFirstCount = tempModel.listStatements(null, tempModel.createProperty("http://www.w3.org/1999/02/22-rdf-syntax-ns#first"), (RDFNode)null).toList().size();
            long rdfRestCount = tempModel.listStatements(null, tempModel.createProperty("http://www.w3.org/1999/02/22-rdf-syntax-ns#rest"), (RDFNode)null).toList().size();
            log.info("TTL加载诊断: ontologyId={}, SWRL: Imp={}, body={}, head={}, ClassAtom={}, DataPropAtom={}, IndPropAtom={}, BuiltinAtom={}, NamedInd={}, rdf:first={}, rdf:rest={}, 总三元组={}",
                    ontologyId, swrlImpCount, swrlBodyCount, swrlHeadCount, classAtomCount, dataPropAtomCount, indPropAtomCount, builtinAtomCount, namedIndCount, rdfFirstCount, rdfRestCount, tempModel.size());

            // 写入 TDB2 命名模型（使用 Dataset 级事务）
            tdb2Dataset.begin(ReadWrite.WRITE);
            try {
                Model namedModel = tdb2Dataset.getNamedModel(ontologyId);
                namedModel.removeAll();
                namedModel.add(tempModel);
                log.info("TTL 内容已加载到 TDB2: ontologyId={}, 三元组数量: {}", ontologyId, namedModel.size());
                tdb2Dataset.commit();
            } finally {
                tdb2Dataset.end();
            }

            // 启动内嵌 Fuseki Server（共享同一个 TDB2 Dataset）
            if (fusekiEmbeddedEnabled) {
                startEmbeddedFuseki();
            }

            // 加载完成后触发推理物化（先清除旧标记，确保 Jena SWRL 也重新执行）
            clearAndRematerialize(ontologyId);
        } catch (Exception e) {
            throw new BusinessException("加载 TTL 内容到 TDB2 失败: " + e.getMessage());
        }
    }

    /**
     * 获取 TDB2 中的命名模型
     */
    public Model getLoadedModel(String ontologyId) {
        tdb2Dataset.begin(ReadWrite.READ);
        try {
            Model model = tdb2Dataset.getNamedModel(ontologyId);
            // 判断是否为空模型（未加载任何数据）
            if (model.isEmpty()) {
                return null;
            }
            return model;
        } finally {
            tdb2Dataset.end();
        }
    }

    /**
     * 检查指定本体是否已在 TDB2 中加载
     */
    public boolean isModelLoaded(String ontologyId) {
        tdb2Dataset.begin(ReadWrite.READ);
        try {
            Model model = tdb2Dataset.getNamedModel(ontologyId);
            return model != null && !model.isEmpty();
        } finally {
            tdb2Dataset.end();
        }
    }

    /**
     * 获取 TDB2 中已加载的所有本体 ID 列表
     */
    public List<String> listLoadedModels() {
        List<String> models = new ArrayList<>();
        tdb2Dataset.begin(ReadWrite.READ);
        try {
            Iterator<String> names = tdb2Dataset.listNames();
            while (names.hasNext()) {
                models.add(names.next());
            }
        } finally {
            tdb2Dataset.end();
        }
        return models;
    }

    /**
     * 获取指定本体的三元组数量
     */
    public long getTripleCount(String ontologyId) {
        tdb2Dataset.begin(ReadWrite.READ);
        try {
            Model model = tdb2Dataset.getNamedModel(ontologyId);
            return model != null ? model.size() : 0;
        } finally {
            tdb2Dataset.end();
        }
    }

    /**
     * 统计 TDB2 中已实例化的 SWRL 规则总数
     * SWRL 规则以 swrl:Imp 类型存储
     */
    public long countSwrlRules() {
        tdb2Dataset.begin(ReadWrite.READ);
        try {
            long totalCount = 0;
            Iterator<String> names = tdb2Dataset.listNames();
            while (names.hasNext()) {
                String modelName = names.next();
                Model model = tdb2Dataset.getNamedModel(modelName);
                if (model != null && !model.isEmpty()) {
                    // 统计 swrl:Imp 类型的三元组数量（即 SWRL 规则数）
                    long count = model.listStatements(null, RDF.type,
                            model.createResource("http://www.w3.org/2003/11/swrl#Imp")).toList().size();
                    totalCount += count;
                }
            }
            log.debug("统计 TDB2 中已实例化的 SWRL 规则总数: {}", totalCount);
            return totalCount;
        } finally {
            tdb2Dataset.end();
        }
    }

    /**
     * 在事务中执行查询操作
     * 解决 TDB2 需要在事务中访问 Model 的问题
     *
     * @param ontologyId 本体 ID
     * @param operation  要执行的操作（接收 Model，返回结果）
     * @param <T>        返回类型
     * @return 操作结果
     */
    public <T> T executeInTransaction(String ontologyId, Function<Model, T> operation) {
        tdb2Dataset.begin(ReadWrite.READ);
        try {
            Model model = tdb2Dataset.getNamedModel(ontologyId);
            if (model == null || model.isEmpty()) {
                return null;
            }
            return operation.apply(model);
        } finally {
            tdb2Dataset.end();
        }
    }

    /**
     * 检查模型是否存在且非空
     */
    public boolean hasModel(String ontologyId) {
        tdb2Dataset.begin(ReadWrite.READ);
        try {
            Model model = tdb2Dataset.getNamedModel(ontologyId);
            return model != null && !model.isEmpty();
        } finally {
            tdb2Dataset.end();
        }
    }

    /**
     * 从 TDB2 中删除指定本体的数据
     */
    public void deleteModel(String ontologyId) {
        try {
            tdb2Dataset.begin(ReadWrite.WRITE);
            try {
                Model model = tdb2Dataset.getNamedModel(ontologyId);
                long oldSize = model.size();
                tdb2Dataset.removeNamedModel(ontologyId);
                tdb2Dataset.commit();
                log.info("已从 TDB2 删除本体: ontologyId={}, 旧三元组数: {}", ontologyId, oldSize);
            } finally {
                tdb2Dataset.end();
            }

            // 启动内嵌 Fuseki Server（共享同一个 TDB2 Dataset）
            if (fusekiEmbeddedEnabled) {
                startEmbeddedFuseki();
            }

            // 清除推理物化缓存
            pelletMaterialized.remove(ontologyId);
            pelletMaterializeFailed.remove(ontologyId);
        } catch (Exception e) {
            log.warn("从 TDB2 删除本体失败: ontologyId={}, error={}", ontologyId, e.getMessage());
        }
        pelletMaterialized.remove(ontologyId);
        pelletMaterializeFailed.remove(ontologyId);
    }

    /**
     * 从 TDB2 中删除指定命名图的数据（用于实例/租户删除时清理）
     */
    public void deleteModelByGraphUri(String graphUri) {
        if (graphUri == null || graphUri.isBlank()) {
            return;
        }
        try {
            tdb2Dataset.begin(ReadWrite.WRITE);
            try {
                Model model = tdb2Dataset.getNamedModel(graphUri);
                long oldSize = model != null ? model.size() : 0;
                tdb2Dataset.removeNamedModel(graphUri);
                tdb2Dataset.commit();
                log.info("已从 TDB2 删除命名图: graphUri={}, 旧三元组数: {}", graphUri, oldSize);
            } finally {
                tdb2Dataset.end();
            }

            if (fusekiEmbeddedEnabled) {
                startEmbeddedFuseki();
            }

            pelletMaterialized.remove(graphUri);
            pelletMaterializeFailed.remove(graphUri);
        } catch (Exception e) {
            log.warn("从 TDB2 删除命名图失败: graphUri={}, error={}", graphUri, e.getMessage());
        }
    }

    // ==================== Pellet OWL-DL + SWRL 推理物化 ====================

    private static final String PELLET_INFERRED_GRAPH_URI = "http://ontologybuilder.local/inferred";

    /**
     * 使用 Pellet 执行 OWL-DL + SWRL 推理，并将推断三元组物化到 TDB2。
     * 物化的三元组存储在独立命名图（避免与原始数据混淆），
     * SPARQL 查询时自动合并原始图 + 推断图。
     *
     * @param ontologyId 本体 ID
     */
    public void materializePelletInferences(String ontologyId) {
        materializePelletInferences(ontologyId, null);
    }

    /**
     * Pellet OWL-DL + SWRL 推理物化（支持 logSink 回调输出进度到 UI）
     */
    public void materializePelletInferences(String ontologyId, BiConsumer<String, String> logSink) {
        // 使用 per-ontologyId 锁防止并发物化导致的数据不一致
        ReentrantLock lock = pelletLocks.computeIfAbsent(ontologyId, k -> new ReentrantLock());
        lock.lock();
        try {
            // 双重检查：获取锁后再次确认是否已完成物化
            if (pelletMaterialized.containsKey(ontologyId)) {
                log.debug("[Pellet] 推理物化已完成（并发等待后检测），跳过: ontologyId={}", ontologyId);
                return;
            }
            long start = System.currentTimeMillis();
            log.info("[Pellet] 开始推理物化: ontologyId={}", ontologyId);
            if (logSink != null) logSink.accept("info", "[Pellet] 开始推理物化");
            updateReasoningProgress(ontologyId, 0, "开始推理");
            // 防御性检查：调用方必须在事务外调用本方法，否则嵌套 begin/end 会导致死锁
            if (tdb2Dataset.isInTransaction()) {
                log.error("[Pellet] 检测到调用方在活跃事务内调用 materializePelletInferences，"
                        + "这会导致嵌套事务死锁！调用栈将指示问题调用方。ontologyId={}", ontologyId);
                throw new IllegalStateException(
                        "materializePelletInferences 必须在 TDB2 事务外调用，检测到活跃事务");
            }
            // 1. 从 TDB2 读取原始模型，在事务内完成 read（TDB2 要求事务内访问）
            updateReasoningProgress(ontologyId, 5, "读取TDB2模型");
            tdb2Dataset.begin(ReadWrite.READ);
            String turtleString;
            int baseTripleCount;
            try {
                Model baseModel = tdb2Dataset.getNamedModel(ontologyId);
                if (baseModel.isEmpty()) {
                    log.warn("[Pellet] 本体为空，跳过推理: {}", ontologyId);
                    tdb2Dataset.end();
                    clearReasoningProgress(ontologyId);
                    return;
                }
                baseTripleCount = (int) baseModel.size();
                ByteArrayOutputStream baos = new ByteArrayOutputStream();
                baseModel.write(baos, "TURTLE");
                turtleString = baos.toString("UTF-8");
            } finally {
                tdb2Dataset.end();
            }

            // 2. 将 Jena Model 转换为 OWLOntology（通过 RDF/Turtle 字符串中转）
            updateReasoningProgress(ontologyId, 15, "转换OWL API本体");
            OWLOntologyManager owlManager = OWLManager.createOWLOntologyManager();
            OWLOntology owlOntology = owlManager.loadOntologyFromOntologyDocument(
                    new ByteArrayInputStream(turtleString.getBytes("UTF-8")));

            updateReasoningProgress(ontologyId, 20, "标准化数据类型");
            normalizeDatatypes(owlOntology, logSink);

            // 3. 使用 Pellet 执行完整 OWL-DL + SWRL 推理
            updateReasoningProgress(ontologyId, 25, "Pellet推理中");
            OWLReasonerFactory pelletFactory = new PelletReasonerFactory();
            OWLReasoner reasoner = pelletFactory.createReasoner(owlOntology);
            try {
                reasoner.precomputeInferences(
                        InferenceType.CLASS_HIERARCHY,
                        InferenceType.OBJECT_PROPERTY_HIERARCHY,
                        InferenceType.DATA_PROPERTY_HIERARCHY);
            } catch (InconsistentOntologyException e) {
                log.warn("[Pellet] 本体不一致，跳过推理物化: {}, reason={}",
                        ontologyId, e.getMessage());
                if (logSink != null) logSink.accept("warn",
                        "[Pellet] 本体不一致: " + e.getMessage());
                // 先输出数据类型不匹配诊断（不依赖 reasoner，安全）
                List<String> datatypeDiags = collectDatatypeMismatchDiagnostics(owlOntology);
                if (!datatypeDiags.isEmpty()) {
                    for (String diag : datatypeDiags) {
                        log.warn("[Pellet] {}", diag);
                        if (logSink != null) logSink.accept("warn", "[Pellet] " + diag);
                    }
                }
                // 尝试收集不可满足类（reasoner 处于不一致状态时可能再次抛异常）
                try {
                    List<String> unsatisfiableClasses = collectUnsatisfiableClasses(owlOntology, reasoner);
                    if (!unsatisfiableClasses.isEmpty()) {
                        String msg = "[Pellet] 不可满足类 (" + unsatisfiableClasses.size() + " 个): "
                                + String.join(", ", unsatisfiableClasses.subList(0,
                                        Math.min(20, unsatisfiableClasses.size())));
                        log.warn("{}", msg);
                        if (logSink != null) logSink.accept("warn", msg);
                    }
                } catch (Exception diagnosticEx) {
                    log.warn("[Pellet] 无法收集不可满足类: {}", diagnosticEx.getMessage());
                    if (logSink != null) logSink.accept("warn",
                            "[Pellet] 无法收集不可满足类: " + diagnosticEx.getMessage());
                }
                // 不设置 pelletMaterialized 标记，不吞异常——调用方需要知道推理未完成
                clearReasoningProgress(ontologyId);
                throw new RuntimeException(
                        "[Pellet] 推理物化失败（本体不一致）: ontologyId=" + ontologyId, e);
            }

            if (!reasoner.isConsistent()) {
                log.warn("[Pellet] 本体不一致，跳过推理物化: {}", ontologyId);
                if (logSink != null) logSink.accept("warn",
                        "[Pellet] 本体不一致（post-check），跳过推理物化");
                List<String> datatypeDiags = collectDatatypeMismatchDiagnostics(owlOntology);
                if (!datatypeDiags.isEmpty()) {
                    for (String diag : datatypeDiags) {
                        log.warn("[Pellet] {}", diag);
                        if (logSink != null) logSink.accept("warn", "[Pellet] " + diag);
                    }
                }
                try {
                    List<String> unsatisfiableClasses = collectUnsatisfiableClasses(owlOntology, reasoner);
                    if (!unsatisfiableClasses.isEmpty()) {
                        String msg = "[Pellet] 不可满足类 (" + unsatisfiableClasses.size() + " 个): "
                                + String.join(", ", unsatisfiableClasses.subList(0,
                                        Math.min(20, unsatisfiableClasses.size())));
                        log.warn("{}", msg);
                        if (logSink != null) logSink.accept("warn", msg);
                    }
                } catch (Exception diagnosticEx) {
                    log.warn("[Pellet] 无法收集不可满足类: {}", diagnosticEx.getMessage());
                    if (logSink != null) logSink.accept("warn",
                            "[Pellet] 无法收集不可满足类: " + diagnosticEx.getMessage());
                }
                // 不设置 pelletMaterialized 标记，不吞异常——调用方需要知道推理未完成
                clearReasoningProgress(ontologyId);
                throw new RuntimeException(
                        "[Pellet] 推理物化失败（本体不一致）: ontologyId=" + ontologyId);
            }

            // 4. 提取推断三元组到 Jena Model
            updateReasoningProgress(ontologyId, 80, "提取推断三元组");
            Model inferredModel = ModelFactory.createDefaultModel();
            String rdfType = "http://www.w3.org/1999/02/22-rdf-syntax-ns#type";
            int inferredCount = 0;

            // 4a. 推断的类型声明（rdf:type）— 包括 SWRL 规则产生的推断
            for (OWLNamedIndividual individual : owlOntology.getIndividualsInSignature()) {
                NodeSet<OWLClass> typeNodes = reasoner.getTypes(individual, false);
                for (OWLClass cls : typeNodes.entities().collect(Collectors.toList())) {
                    // 跳过 owl:Thing 和 owl:Nothing
                    if (cls.isOWLThing() || cls.isOWLNothing()) continue;
                    // 检查是否为显式声明（已在原始 TTL 中）
                    boolean isExplicit = owlOntology.getClassAssertionAxioms(individual).stream()
                            .anyMatch(ax -> ax.getClassExpression().isOWLClass()
                                    && ax.getClassExpression().asOWLClass().equals(cls));
                    if (!isExplicit) {
                        Resource s = ResourceFactory.createResource(individual.getIRI().toString());
                        Property p = ResourceFactory.createProperty(rdfType);
                        Resource o = ResourceFactory.createResource(cls.getIRI().toString());
                        inferredModel.add(s, p, o);
                        inferredCount++;
                    }
                }
            }

            // 4b. 推断的对象属性值（SWRL 规则产生的属性断言）
            for (OWLObjectProperty prop : owlOntology.getObjectPropertiesInSignature()) {
                for (OWLNamedIndividual individual : owlOntology.getIndividualsInSignature()) {
                    NodeSet<OWLNamedIndividual> valueNodes = reasoner.getObjectPropertyValues(individual, prop);
                    for (OWLNamedIndividual value : valueNodes.entities().collect(Collectors.toList())) {
                        // 检查是否为显式声明
                        boolean isExplicit = owlOntology.getObjectPropertyAssertionAxioms(individual).stream()
                                .anyMatch(ax -> ax.getProperty().equals(prop) && ax.getObject().equals(value));
                        if (!isExplicit) {
                            Resource s = ResourceFactory.createResource(individual.getIRI().toString());
                            Property p = ResourceFactory.createProperty(prop.getIRI().toString());
                            Resource o = ResourceFactory.createResource(value.getIRI().toString());
                            inferredModel.add(s, p, o);
                            inferredCount++;
                        }
                    }
                }
            }

            // 4c. 推断的数据属性值（SWRL 规则产生的数据属性断言）
            for (OWLDataProperty prop : owlOntology.getDataPropertiesInSignature()) {
                for (OWLNamedIndividual individual : owlOntology.getIndividualsInSignature()) {
                    Set<OWLLiteral> values = reasoner.getDataPropertyValues(individual, prop);
                    for (OWLLiteral value : values) {
                        boolean isExplicit = owlOntology.getDataPropertyAssertionAxioms(individual).stream()
                                .anyMatch(ax -> ax.getProperty().equals(prop) && ax.getObject().equals(value));
                        if (!isExplicit) {
                            Resource s = ResourceFactory.createResource(individual.getIRI().toString());
                            Property p = ResourceFactory.createProperty(prop.getIRI().toString());
                            Literal o = ResourceFactory.createTypedLiteral(value.getLiteral());
                            inferredModel.add(s, p, o);
                            inferredCount++;
                        }
                    }
                }
            }

            // 5. 将推断三元组写入 TDB2
            if (logSink != null) logSink.accept("info", String.format("[Pellet] 写入 TDB2: %d 条推断", inferredCount));
            updateReasoningProgress(ontologyId, 80, "写入TDB2");
            tdb2Dataset.begin(ReadWrite.WRITE);
            try {
                Model namedModel = tdb2Dataset.getNamedModel(ontologyId);
                namedModel.add(inferredModel);
                tdb2Dataset.commit();
            } finally {
                tdb2Dataset.end();
            }

            // 6. 使用 Jena GenericRuleReasoner 执行 SWRL 规则（需要 builtins 支持）
            updateReasoningProgress(ontologyId, 90, "执行Jena SWRL规则");
            if (logSink != null) logSink.accept("info", "[SWRL] 开始 SWRL 规则物化...");
            long jenaInferred = materializeJenaSwrlRules(ontologyId, logSink);
            long totalInferred = inferredCount + jenaInferred;

            pelletMaterialized.put(ontologyId, true);
            pelletMaterializeFailed.remove(ontologyId); // 成功后清除失败标记
            markPelletMaterializedInTdb2(ontologyId);
            long elapsed = System.currentTimeMillis() - start;
            log.info("[Pellet] 推理物化完成: ontologyId={}, 推断三元组数={} (Pellet={}, Jena-SWRL={}), 耗时={}ms",
                    ontologyId, totalInferred, inferredCount, jenaInferred, elapsed);
            if (logSink != null) logSink.accept("success",
                    String.format("[Pellet] 推理物化完成: 共 %d 条推断 (Pellet=%d, SWRL=%d), 耗时 %dms",
                            totalInferred, inferredCount, jenaInferred, elapsed));
            updateReasoningProgress(ontologyId, 100, "已完成");
        } catch (Exception e) {
            log.error("[Pellet] 推理物化失败: ontologyId={}, error={}", ontologyId, e.getMessage(), e);
            if (logSink != null) logSink.accept("error", "[Pellet] 推理物化失败: " + e.getMessage());
            // 推理失败时清除进度，记录失败标记防止死循环重试
            clearReasoningProgress(ontologyId);
            pelletMaterializeFailed.put(ontologyId, e.getMessage());
            throw new RuntimeException(
                    "[Pellet] 推理物化失败: ontologyId=" + ontologyId + ", error=" + e.getMessage(), e);
        } finally {
            lock.unlock();
        }
    }

    /**
     * 清除已物化的推断三元组并重建（TTL 重新加载时调用）
     */
    public void clearAndRematerialize(String ontologyId) {
        clearAndRematerialize(ontologyId, null);
    }

    /**
     * 清除已物化的推断三元组并重建，支持进度回调。
     */
    public void clearAndRematerialize(String ontologyId, BiConsumer<String, String> logSink) {
        if (logSink != null) logSink.accept("info", "清除旧推理结果...");
        pelletMaterialized.remove(ontologyId);
        pelletMaterializeFailed.remove(ontologyId); // 清除失败标记，允许重新尝试
        removePelletMaterializedFromTdb2(ontologyId);
        if (logSink != null) logSink.accept("info", "开始 Pellet + SWRL 推理物化...");
        materializePelletInferences(ontologyId, logSink);
        if (logSink != null) logSink.accept("success", "推理物化完成");
    }

    // ==================== Jena GenericRuleReasoner SWRL 规则物化 ====================

    private static final String SWRL_NS = "http://www.w3.org/2003/11/swrl#";
    private static final String SWRLB_NS = "http://www.w3.org/2003/11/swrlb#";

    /**
     * 使用 Jena GenericRuleReasoner 执行 SWRL 规则物化。
     * 必须在 Pellet OWL-DL 推理之后调用 —— SWRL 规则可能依赖 Pellet 推断的类层次关系。
     *
     * @param ontologyId 本体 ID
     * @return 推断的新三元组数量
     */
    private long materializeJenaSwrlRules(String ontologyId) {
        return materializeJenaSwrlRules(ontologyId, null);
    }

    private long materializeJenaSwrlRules(String ontologyId, BiConsumer<String, String> logSink) {
        long start = System.currentTimeMillis();
        log.info("[Jena-SWRL] 开始 SWRL 规则物化: ontologyId={}", ontologyId);
        if (logSink != null) logSink.accept("info", "[SWRL] 开始 SWRL 规则物化...");

        // 从 TDB2 读取模型并复制到内存模型（TDB2 Model 需要活跃事务，不能在事务外使用）
        tdb2Dataset.begin(ReadWrite.READ);
        Model baseModel;
        try {
            Model tdb2Model = tdb2Dataset.getNamedModel(ontologyId);
            if (tdb2Model.isEmpty()) {
                log.warn("[Jena-SWRL] 本体为空，跳过 SWRL 物化: {}", ontologyId);
                return 0;
            }
            baseModel = ModelFactory.createDefaultModel();
            baseModel.add(tdb2Model);
        } finally {
            tdb2Dataset.end();
        }

        // 转换 SWRL 规则 → Jena Rule（key = SWRL Imp URI）
        Map<String, Rule> ruleByUri = convertSwrlToJenaRules(baseModel);
        if (ruleByUri.isEmpty()) {
            log.info("[Jena-SWRL] 未发现 SWRL 规则，跳过推理: ontologyId={}", ontologyId);
            return 0;
        }
        log.info("[Jena-SWRL] 共转换 {} 条 SWRL 规则", ruleByUri.size());
        if (logSink != null) logSink.accept("info", "[SWRL] 规则转换: " + ruleByUri.size() + " 条");

        // FC/BC 分类
        SwrlRuleClassifier.ClassificationResult classification = swrlClassifier.classify(baseModel);
        log.info("[Jena-SWRL] 规则分类: {} FC + {} BC",
                classification.getFcRules().size(), classification.getBcRules().size());
        if (logSink != null) logSink.accept("info", "[SWRL] 规则分类: " + classification.getFcRules().size() + " FC + " + classification.getBcRules().size() + " BC");

        // 构建 BC 谓词索引（用于 SPARQL 查询拦截）
        if (classification.hasBcRules()) {
            Map<String, List<RuleProfile>> predicateIndex = new HashMap<>();
            for (RuleProfile profile : classification.getBcRules()) {
                for (String headPred : profile.getHeadPredicates()) {
                    predicateIndex.computeIfAbsent(headPred, k -> new ArrayList<>()).add(profile);
                }
            }
            bcPredicateIndex.put(ontologyId, predicateIndex);

            // 更新 SPARQL 拦截器
            SparqlQueryInterceptor interceptor = new SparqlQueryInterceptor();
            interceptor.setBcPredicates(predicateIndex.keySet());
            interceptorCache.put(ontologyId, interceptor);
            log.info("[Jena-SWRL] BC 谓词索引: {} predicates for {}", predicateIndex.size(), ontologyId);
        }

        // 构建 FC-only Jena Rule 列表（通过 SWRL Imp URI 匹配，避免中英文名称不一致）
        Set<String> fcRuleIds = classification.getFcRules().stream()
                .map(RuleProfile::getRuleId)
                .collect(Collectors.toSet());
        List<Rule> fcJenaRules = new ArrayList<>();
        for (Map.Entry<String, Rule> entry : ruleByUri.entrySet()) {
            if (fcRuleIds.contains(entry.getKey())) {
                fcJenaRules.add(entry.getValue());
            }
        }
        log.info("[Jena-SWRL] FC 规则: {} / {} 条", fcJenaRules.size(), ruleByUri.size());

        int newTripleCount = 0;
        if (!fcJenaRules.isEmpty()) {
            // ---- 收集 FC 规则的 head 属性 ----
            java.util.Set<String> allCompHeadProps = new java.util.LinkedHashSet<>();
            for (Rule r : fcJenaRules) {
                allCompHeadProps.addAll(extractPropertyRefs(r, true));
            }
            // DEBUG: 诊断 head 属性检测
            if (!fcJenaRules.isEmpty()) {
                String rule0Str = fcJenaRules.get(0).toString();
                int dbgArrow = rule0Str.indexOf(" -> ");
                log.info("[Jena-SWRL] DEBUG allCompHeadProps={} ({}条), rule[0] head片段={}",
                        allCompHeadProps, allCompHeadProps.size(),
                        dbgArrow > 0 ? rule0Str.substring(dbgArrow, Math.min(dbgArrow + 120, rule0Str.length())) : "NO_ARROW");
            }

            // ---- 0) 清除 head 属性旧值，注入初始默认值 ----
            // SWRL 规则只能新增三元组无法删除，推理前必须清除 head 属性的已有值
            // （如 LLM 在 TTL 中预设的默认值），否则推断值会与旧值并存。
            // 默认值从已有数据中自动推导：取目标实例上该属性出现次数最多的值。
            int headResetCount = 0;
            for (String headPropUri : allCompHeadProps) {
                Property headProp = ResourceFactory.createProperty(headPropUri);
                // 收集所有涉及此 head 属性的目标类实例及其旧值
                java.util.Set<Resource> headInstances = new java.util.LinkedHashSet<>();
                java.util.Map<String, Integer> valueFrequency = new java.util.LinkedHashMap<>();
                for (Rule rule : fcJenaRules) {
                    if (!extractPropertyRefs(rule, true).contains(headPropUri)) continue;
                    java.util.List<String> targetClassUris = extractAllTargetClassesFromRule(rule);
                    for (String targetClassUri : targetClassUris) {
                        Resource targetType = ResourceFactory.createResource(targetClassUri);
                        java.util.List<Resource> instances = baseModel.listResourcesWithProperty(RDF.type, targetType).toList();
                        for (Resource inst : instances) {
                            headInstances.add(inst);
                            StmtIterator it = baseModel.listStatements(inst, headProp, (RDFNode) null);
                            while (it.hasNext()) {
                                String val = it.next().getObject().toString();
                                valueFrequency.merge(val, 1, Integer::sum);
                            }
                        }
                    }
                }
                // 第二轮：扫描 baseModel 中所有持有该 head 属性的实例（含 LLM 误放到非目标类上的）
                // 杂散实例只做清理（注入默认值），不参与频率统计（避免污染默认值推导）
                java.util.List<Statement> allHeadStmts = baseModel.listStatements(null, headProp, (RDFNode) null).toList();
                for (Statement stmt : allHeadStmts) {
                    Resource s = stmt.getSubject();
                    if (!headInstances.contains(s)) {
                        headInstances.add(s);
                    }
                }
                // 推导默认值：取出现频率最高的值；若无旧值则用 0
                Literal defaultValue;
                String mostFrequent = valueFrequency.entrySet().stream()
                        .max(java.util.Map.Entry.comparingByValue())
                        .map(java.util.Map.Entry::getKey).orElse(null);
                if (mostFrequent != null) {
                    // 保持原始字面量类型（字符串/数值）
                    defaultValue = ResourceFactory.createPlainLiteral(
                            mostFrequent.replaceAll("^\"|\"\\^\\^.*$|\"$", "").replaceAll("^'|'$", ""));
                    // 尝试还原为数值字面量
                    try {
                        double d = Double.parseDouble(defaultValue.getLexicalForm());
                        defaultValue = ResourceFactory.createTypedLiteral(d);
                    } catch (NumberFormatException ignored) { }
                } else {
                    defaultValue = ResourceFactory.createTypedLiteral(0);
                }
                // 为每个实例：移除旧值 → 注入默认值
                for (Resource inst : headInstances) {
                    baseModel.removeAll(inst, headProp, null);
                    baseModel.add(inst, headProp, defaultValue);
                    headResetCount++;
                }
            }
            log.info("[Jena-SWRL] Head 属性初始化完成: headResetCount={}, allCompHeadProps.size()={}",
                    headResetCount, allCompHeadProps.size());

            // ---- 1) 注入默认值：仅非中间属性（raw data props） ----
            int preDefaults = 0;
            for (Rule rule : fcJenaRules) {
                java.util.List<String> bodyPropUris = extractBodyPropsFromSumRule(rule);
                java.util.List<String> targetClassUris = extractAllTargetClassesFromRule(rule);
                if (bodyPropUris.isEmpty() || targetClassUris.isEmpty()) continue;
                for (String targetClassUri : targetClassUris) {
                    Resource targetType = ResourceFactory.createResource(targetClassUri);
                    java.util.List<Resource> instances = baseModel.listResourcesWithProperty(RDF.type, targetType).toList();
                    for (Resource inst : instances) {
                        for (String propUri : bodyPropUris) {
                            if (allCompHeadProps.contains(propUri)) continue;
                            Property prop = ResourceFactory.createProperty(propUri);
                            if (!inst.hasProperty(prop)) {
                                baseModel.add(inst, prop, ResourceFactory.createTypedLiteral(0));
                                preDefaults++;
                            }
                        }
                    }
                }
            }
            log.info("[Jena-SWRL] 前置注入 {} 条默认值(raw props)", preDefaults);

            // ---- 2) 第一轮 OxiRS → leaf 规则触发，产出部分中间属性 ----
            Model inferredModel = ModelFactory.createDefaultModel();
            boolean useOxiRS = oxirsClient != null && oxirsClient.isEnabled();
            if (useOxiRS) {
                try {
                    List<com.ontologybuilder.oxirs.grpc.Rule> fcGrpcRules = convertProfilesToGrpcRules(
                            classification.getFcRules(), baseModel);
                    List<com.ontologybuilder.oxirs.grpc.Triple> baseGrpcTriples = IncrementalUpdateService.convertToGrpcTriples(baseModel);
                    com.ontologybuilder.oxirs.grpc.ForwardChainResponse resp = oxirsClient.forwardChain(
                            baseGrpcTriples, fcGrpcRules, 3, false);
                    if (resp.getSuccess() && resp.getInferredTriplesCount() > 0) {
                        Model round1 = IncrementalUpdateService.convertFromGrpcTriples(resp.getInferredTriplesList());
                        baseModel.add(round1);
                        inferredModel.add(round1);
                        log.info("[Jena-SWRL] 第一轮 OxiRS → {} 条推断 ({}ms)", round1.size(), resp.getElapsedMs());
                        if (logSink != null) logSink.accept("success", "[SWRL] OxiRS 第1轮: " + round1.size() + " 条推断 (" + resp.getElapsedMs() + "ms)");
                    } else if (!resp.getSuccess()) {
                        log.warn("[Jena-SWRL] 第一轮 OxiRS 失败: {}", resp.getErrorMessage());
                        if (logSink != null) logSink.accept("warn", "[SWRL] OxiRS 第1轮失败: " + resp.getErrorMessage());
                        useOxiRS = false;
                    } else {
                        // OxiRS 成功但返回 0 条推断 → 回退到 Jena GenericRuleReasoner
                        log.info("[Jena-SWRL] 第一轮 OxiRS 返回 0 条推断，回退到 Jena GenericRuleReasoner");
                        if (logSink != null) logSink.accept("warn", "[SWRL] OxiRS 返回 0 条，回退到 Jena");
                        useOxiRS = false;
                    }
                } catch (Exception e) {
                    log.warn("[Jena-SWRL] 第一轮 OxiRS 异常: {} - {}", e.getClass().getSimpleName(), e.getMessage(), e);
                    if (logSink != null) logSink.accept("error", "[SWRL] OxiRS 第1轮异常: " + e.getMessage());
                    useOxiRS = false;
                }
            }

            // ---- 3) OxiRS 第二轮：注入中间属性默认值，再跑一轮让 Aggregate 规则触发 ----
            if (useOxiRS) {
                int midDefaults = 0;
                for (Rule rule : fcJenaRules) {
                    java.util.List<String> bodyPropUris = extractBodyPropsFromSumRule(rule);
                    java.util.List<String> targetClassUris = extractAllTargetClassesFromRule(rule);
                    if (bodyPropUris.isEmpty() || targetClassUris.isEmpty()) continue;
                    for (String targetClassUri : targetClassUris) {
                        Resource targetType = ResourceFactory.createResource(targetClassUri);
                        java.util.List<Resource> instances = baseModel.listResourcesWithProperty(RDF.type, targetType).toList();
                        for (Resource inst : instances) {
                            for (String propUri : bodyPropUris) {
                                if (!allCompHeadProps.contains(propUri)) continue;
                                Property prop = ResourceFactory.createProperty(propUri);
                                if (!inst.hasProperty(prop)) {
                                    baseModel.add(inst, prop, ResourceFactory.createTypedLiteral(0));
                                    midDefaults++;
                                }
                            }
                        }
                    }
                }
                if (midDefaults > 0) {
                    log.info("[Jena-SWRL] 中间属性补默认值 {} 条", midDefaults);
                }
                // ---- 4) 第二轮 OxiRS → Aggregate 规则消费完整数据 ----
                if (midDefaults > 0) {
                try {
                    List<com.ontologybuilder.oxirs.grpc.Rule> fcGrpcRules = convertProfilesToGrpcRules(
                            classification.getFcRules(), baseModel);
                    List<com.ontologybuilder.oxirs.grpc.Triple> baseGrpcTriples = IncrementalUpdateService.convertToGrpcTriples(baseModel);
                    com.ontologybuilder.oxirs.grpc.ForwardChainResponse resp = oxirsClient.forwardChain(
                            baseGrpcTriples, fcGrpcRules, 3, false);
                    if (resp.getSuccess() && resp.getInferredTriplesCount() > 0) {
                        Model round2 = IncrementalUpdateService.convertFromGrpcTriples(resp.getInferredTriplesList());
                        baseModel.add(round2);
                        inferredModel.add(round2);
                        log.info("[Jena-SWRL] 第二轮 OxiRS → {} 条推断 ({}ms)", round2.size(), resp.getElapsedMs());
                        if (logSink != null) logSink.accept("success", "[SWRL] OxiRS 第2轮: " + round2.size() + " 条推断 (" + resp.getElapsedMs() + "ms)");
                    } else if (!resp.getSuccess()) {
                        log.warn("[Jena-SWRL] 第二轮 OxiRS 失败: {}", resp.getErrorMessage());
                        if (logSink != null) logSink.accept("warn", "[SWRL] OxiRS 第2轮失败: " + resp.getErrorMessage());
                    }
                } catch (Exception e) {
                    log.warn("[Jena-SWRL] 第二轮 OxiRS 异常: {}", e.getMessage());
                    if (logSink != null) logSink.accept("error", "[SWRL] OxiRS 第2轮异常: " + e.getMessage());
                }
                } // if (midDefaults > 0)
            } // if (useOxiRS)

            // ---- Jena 降级：OxiRS 不可用时使用 Jena GenericRuleReasoner ----
            // 关键优化：将自引用规则（body 和 head 含同一属性，如 sum(?cur,5,?new)→同一属性）
            // 与普通规则分开处理，避免自引用循环在前向推理中导致组合爆炸
            if (!useOxiRS) {
                java.util.List<Rule> normalRules = new java.util.ArrayList<>();
                java.util.List<Rule> selfRefRules = new java.util.ArrayList<>();
                for (Rule r : fcJenaRules) {
                    if (isSelfReferencingRule(r)) {
                        selfRefRules.add(r);
                    } else {
                        normalRules.add(r);
                    }
                }
                log.info("[Jena-SWRL] 规则拆分: {} normal (无自引用) + {} self-referencing (自引用累加器)",
                        normalRules.size(), selfRefRules.size());
                for (Rule sr : selfRefRules) {
                    log.info("[Jena-SWRL]   selfRef: {}", sr.getName());
                }

                // Phase 1: 普通规则 → 快速收敛（DAG 无环，通常 1-3 轮即固定点）
                if (!normalRules.isEmpty()) {
                    log.info("[Jena-SWRL] Phase 1: {} normal rules → Jena FC", normalRules.size());
                    GenericRuleReasoner reasoner = new GenericRuleReasoner(normalRules);
                    reasoner.setMode(GenericRuleReasoner.FORWARD);
                    InfModel infModel = ModelFactory.createInfModel(reasoner, baseModel);
                    Future<Void> fcFuture = jenaFcExecutor.submit(() -> {
                        infModel.prepare();
                        return null;
                    });
                    try {
                        fcFuture.get(JENA_FC_TIMEOUT_SEC, TimeUnit.SECONDS);
                    } catch (TimeoutException e) {
                        log.error("[Jena-SWRL] Phase 1 normal rules FC 超时（{}s），终止 Jena 降级推理。" +
                                " 请检查 OxiRS 服务是否可用。", JENA_FC_TIMEOUT_SEC);
                        fcFuture.cancel(true);
                    } catch (ExecutionException e) {
                        log.warn("[Jena-SWRL] Phase 1 normal rules FC 执行异常: {} - {}",
                                e.getCause() != null ? e.getCause().getClass().getSimpleName() : "Unknown",
                                e.getCause() != null ? e.getCause().getMessage() : e.getMessage());
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        log.warn("[Jena-SWRL] Phase 1 normal rules FC 被中断");
                        fcFuture.cancel(true);
                    }
                    if (!fcFuture.isCancelled()) {
                        Model jenaNew1 = infModel.difference(baseModel);
                        if (jenaNew1.size() > 0) {
                            baseModel.add(jenaNew1);
                            inferredModel.add(jenaNew1);
                            log.info("[Jena-SWRL] Phase 1 normal rules → {} 条推断", jenaNew1.size());
                        }
                    }
                }

                // Phase 2: 自引用累加规则 → 仅允许少量迭代（避免组合爆炸）
                // 自引用规则（如 sum(?cur, 5, ?new) → sameProp）在 fixpoint 前向推理中呈指数增长。
                // 实际语义是"对基础分加奖金"——1-2 轮即可得到正确结果。
                if (!selfRefRules.isEmpty()) {
                    log.info("[Jena-SWRL] Phase 2: {} selfRef rules → Jena FC (max 30s)", selfRefRules.size());
                    GenericRuleReasoner srReasoner = new GenericRuleReasoner(selfRefRules);
                    srReasoner.setMode(GenericRuleReasoner.FORWARD);
                    InfModel srInfModel = ModelFactory.createInfModel(srReasoner, baseModel);
                    Future<Void> srFuture = jenaFcExecutor.submit(() -> {
                        srInfModel.prepare();
                        return null;
                    });
                    try {
                        // 自引用规则超时设短，只拿 1-2 轮迭代结果
                        srFuture.get(30, TimeUnit.SECONDS);
                    } catch (TimeoutException e) {
                        log.warn("[Jena-SWRL] Phase 2 selfRef rules 超时（30s），跳过。" +
                                " 已累积 {} 条推断（含普通规则结果），自引用累加器未完成但任务可继续。", inferredModel.size());
                        srFuture.cancel(true);
                    } catch (ExecutionException e) {
                        log.warn("[Jena-SWRL] Phase 2 selfRef rules 执行异常: {} - {}",
                                e.getCause() != null ? e.getCause().getClass().getSimpleName() : "Unknown",
                                e.getCause() != null ? e.getCause().getMessage() : e.getMessage());
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        log.warn("[Jena-SWRL] Phase 2 selfRef rules 被中断");
                        srFuture.cancel(true);
                    }
                    if (!srFuture.isCancelled()) {
                        Model srNew = srInfModel.difference(baseModel);
                        if (srNew.size() > 0) {
                            baseModel.add(srNew);
                            inferredModel.add(srNew);
                            log.info("[Jena-SWRL] Phase 2 selfRef rules → {} 条推断", srNew.size());
                        }
                        // 同 S+P 多值去重（仅保留最大值）
                        java.util.Map<String, java.util.List<Statement>> spGroups = new java.util.LinkedHashMap<>();
                        java.util.List<Statement> allStmts = inferredModel.listStatements().toList();
                        for (Statement stmt : allStmts) {
                            spGroups.computeIfAbsent(
                                    stmt.getSubject().getURI() + "|" + stmt.getPredicate().getURI(),
                                    k -> new java.util.ArrayList<>()).add(stmt);
                        }
                        int deduped = 0;
                        for (java.util.Map.Entry<String, java.util.List<Statement>> e : spGroups.entrySet()) {
                            if (e.getValue().size() <= 1) continue;
                            Statement maxStmt = e.getValue().get(0);
                            double maxVal = Double.NEGATIVE_INFINITY;
                            for (Statement s : e.getValue()) {
                                if (!s.getObject().isLiteral()) continue;
                                try {
                                    double v = Double.parseDouble(s.getObject().asLiteral().getLexicalForm());
                                    if (v > maxVal) { maxVal = v; maxStmt = s; }
                                } catch (NumberFormatException ex) { maxStmt = s; }
                            }
                            inferredModel.remove(maxStmt.getSubject(), maxStmt.getPredicate(), (RDFNode) null);
                            inferredModel.add(maxStmt);
                            deduped += e.getValue().size() - 1;
                        }
                        if (deduped > 0) log.info("[Jena-SWRL] Phase 2 去重 {} 条（同S+P保留最大值）", deduped);
                    }
                }

                // 注入中间属性默认值（未计算到的分量补 0）
                int jenaMidDefaults = 0;
                for (Rule rule : fcJenaRules) {
                    java.util.List<String> bodyPropUris = extractBodyPropsFromSumRule(rule);
                    java.util.List<String> targetClassUris = extractAllTargetClassesFromRule(rule);
                    if (bodyPropUris.isEmpty() || targetClassUris.isEmpty()) continue;
                    for (String targetClassUri : targetClassUris) {
                        Resource targetType = ResourceFactory.createResource(targetClassUri);
                        java.util.List<Resource> instances = baseModel.listResourcesWithProperty(RDF.type, targetType).toList();
                        for (Resource inst : instances) {
                            for (String propUri : bodyPropUris) {
                                if (!allCompHeadProps.contains(propUri)) continue;
                                Property prop = ResourceFactory.createProperty(propUri);
                                if (!inst.hasProperty(prop)) {
                                    baseModel.add(inst, prop, ResourceFactory.createTypedLiteral(0));
                                    jenaMidDefaults++;
                                }
                            }
                        }
                    }
                }
                if (jenaMidDefaults > 0) {
                    log.info("[Jena-SWRL] 中间属性补默认值 {} 条", jenaMidDefaults);
                }
            }

            // ---- Jena 补充验证：仅当 OxiRS 未产出结果时，用 Jena 兜底 ----
            // OxiRS 支持全部 SWRL builtin（含 concat/abs/not/contains），Jena 不支持这些，
            // 因此 OxiRS 有结果时无需 Jena 重复验证，避免 120s 无效等待
            if (useOxiRS && inferredModel.isEmpty()) {
                GenericRuleReasoner reasoner = new GenericRuleReasoner(fcJenaRules);
                reasoner.setMode(GenericRuleReasoner.FORWARD);
                InfModel infModel = ModelFactory.createInfModel(reasoner, baseModel);
                Future<Void> verifyFuture = jenaFcExecutor.submit(() -> {
                    infModel.prepare();
                    return null;
                });
                try {
                    verifyFuture.get(120, TimeUnit.SECONDS);
                    Model jenaNew = infModel.difference(baseModel);
                    if (jenaNew.size() > 0) {
                        baseModel.add(jenaNew);
                        inferredModel.add(jenaNew);
                        log.info("[Jena-SWRL] Jena 补充验证 → {} 条推断（OxiRS 遗漏补全）", jenaNew.size());
                    }
                } catch (TimeoutException e) {
                    log.warn("[Jena-SWRL] Jena 补充验证超时（120s），跳过。OxiRS 推断结果已足够，但建议人工复用验证。");
                    if (logSink != null) logSink.accept("warn", "[SWRL] Jena 补充验证超时（120s），跳过");
                    verifyFuture.cancel(true);
                } catch (ExecutionException e) {
                    log.warn("[Jena-SWRL] Jena 补充验证异常: {}", e.getMessage());
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    verifyFuture.cancel(true);
                }
            }

            // ---- 通用去重：同 S+P 保留最大值 ----
            java.util.Map<String, java.util.List<Statement>> spGroups = new java.util.LinkedHashMap<>();
            for (Statement stmt : inferredModel.listStatements().toList()) {
                spGroups.computeIfAbsent(
                        stmt.getSubject().getURI() + "|" + stmt.getPredicate().getURI(),
                        k -> new java.util.ArrayList<>()).add(stmt);
            }
            int deduped = 0;
            for (java.util.Map.Entry<String, java.util.List<Statement>> e : spGroups.entrySet()) {
                if (e.getValue().size() <= 1) continue;
                Statement best = e.getValue().get(0);
                double bestVal = Double.NEGATIVE_INFINITY;
                for (Statement s : e.getValue()) {
                    if (!s.getObject().isLiteral()) continue; // 跳过非字面量（如 predicted_result → Team_11 URI）
                    try {
                        double v = Double.parseDouble(s.getObject().asLiteral().getLexicalForm());
                        if (v > bestVal) { bestVal = v; best = s; }
                    } catch (NumberFormatException ex) { best = s; }
                }
                // 移除该 S+P 的所有旧值，再写入最大值
                inferredModel.removeAll(best.getSubject(), best.getPredicate(), null);
                inferredModel.add(best);
                deduped += e.getValue().size() - 1;
            }
            if (deduped > 0) {
                log.info("[Jena-SWRL] 去重 {} 条（同S+P保留最大值）", deduped);
                if (logSink != null) logSink.accept("info", "[SWRL] 去重 " + deduped + " 条（同S+P保留最大值）");
            }

            // ---- 字符串 head 属性去重 ----
            // 对于字符串值的 head 属性，如果同 S+P 存在多个值（默认值 + 推断值），
            // 保留非默认值。通用策略：默认值为出现次数最多的值（step 0 注入），
            // 其余值为规则推断结果；若多条规则均触发则取规则列表中靠前者（优先级更高）。
            java.util.Map<String, Integer> ruleOrder = new java.util.LinkedHashMap<>();
            for (int i = 0; i < fcJenaRules.size(); i++) {
                // 使用 rule.toString() 拆分获取 head 文本（getHead() 返回数组引用，不可用）
                String ruleStr = fcJenaRules.get(i).toString();
                int rArrow = ruleStr.indexOf(" -> ");
                String headText = rArrow > 0 ? ruleStr.substring(rArrow + 4) : ruleStr;
                for (String headPropUri : allCompHeadProps) {
                    java.util.regex.Matcher hm = java.util.regex.Pattern.compile(
                            "<" + java.util.regex.Pattern.quote(headPropUri) + ">\\s+([^)]+)\\)")
                            .matcher(headText);
                    if (hm.find()) {
                        // 用词法形式（去引号）做 key，确保与 Literal.getLexicalForm() 匹配
                        String rawVal = hm.group(1).trim();
                        String lexical = rawVal.replaceAll("^['\"]|['\"]$", "");
                        ruleOrder.putIfAbsent(headPropUri + "|" + lexical, i);
                    }
                }
            }
            int strDeduped = 0;
            for (java.util.Map.Entry<String, java.util.List<Statement>> e : spGroups.entrySet()) {
                if (e.getValue().size() <= 1) continue;
                Statement first = e.getValue().get(0);
                if (!first.getObject().isLiteral()) continue;
                // 跳过纯数值属性（已被上方数值去重处理）
                try {
                    Double.parseDouble(first.getObject().asLiteral().getLexicalForm());
                    continue;
                } catch (NumberFormatException ignored) { }
                if (!allCompHeadProps.contains(first.getPredicate().getURI())) continue;

                // 按规则顺序选优先级最高的值（规则 index 越小优先级越高）
                Statement best = null;
                int bestOrder = Integer.MAX_VALUE;
                for (Statement s : e.getValue()) {
                    if (!s.getObject().isLiteral()) continue; // 同S+P组内可能混有URI和字面量
                    String lexical = s.getObject().asLiteral().getLexicalForm();
                    String key = s.getPredicate().getURI() + "|" + lexical;
                    int order = ruleOrder.getOrDefault(key, Integer.MAX_VALUE);
                    if (order <= bestOrder) { bestOrder = order; best = s; }
                }
                if (best != null) {
                    inferredModel.removeAll(best.getSubject(), best.getPredicate(), null);
                    inferredModel.add(best);
                    strDeduped += e.getValue().size() - 1;
                }
            }
            if (strDeduped > 0) log.info("[Jena-SWRL] 去重 {} 条（字符串head属性，按规则优先级）", strDeduped);

            // ---- 数值精度规范化：消除浮点误差 + 统一 integer 类型 ----
            normalizeNumericPrecision(baseModel);
            normalizeNumericPrecision(inferredModel);

            newTripleCount = (int) inferredModel.size();
            if (newTripleCount > 0) {
                // 诊断：关键谓词分布
                java.util.Map<String, java.util.Set<String>> predVals = new java.util.LinkedHashMap<>();
                for (Statement stmt : inferredModel.listStatements().toList()) {
                    predVals.computeIfAbsent(stmt.getPredicate().getURI(), k -> new java.util.LinkedHashSet<>())
                            .add(stmt.getObject().toString());
                }
                log.info("[Jena-SWRL] 推断谓词分布 ({} 条, {} 个谓词):", newTripleCount, predVals.size());
                for (java.util.Map.Entry<String, java.util.Set<String>> e : predVals.entrySet()) {
                    log.info("[Jena-SWRL]   {} → {} 个不同值: {}", e.getKey(), e.getValue().size(), e.getValue());
                }

                tdb2Dataset.begin(ReadWrite.WRITE);
                try {
                    Model namedModel = tdb2Dataset.getNamedModel(ontologyId);
                    // 对于 head 属性：先移除 TDB2 中的旧值，再从 baseModel 写入最终值
                    // （SWRL 规则只能新增三元组，无法删除 TDB2 中原有的默认值）
                    for (String headPropUri : allCompHeadProps) {
                        Property headProp = ResourceFactory.createProperty(headPropUri);
                        // 第一轮：规则目标类实例（应该持有该属性）
                        java.util.Set<Resource> targetInstances = new java.util.LinkedHashSet<>();
                        for (Rule rule : fcJenaRules) {
                            if (!extractPropertyRefs(rule, true).contains(headPropUri)) continue;
                            for (String targetClassUri : extractAllTargetClassesFromRule(rule)) {
                                Resource targetType = ResourceFactory.createResource(targetClassUri);
                                targetInstances.addAll(
                                        namedModel.listResourcesWithProperty(RDF.type, targetType).toList());
                            }
                        }
                        // 所有实例 = 目标类 + 杂散（LLM 误放）→ 全部移除旧值
                        java.util.Set<Resource> allInstances = new java.util.LinkedHashSet<>(targetInstances);
                        java.util.List<Statement> strayStmts = namedModel.listStatements(null, headProp, (RDFNode) null).toList();
                        for (Statement stmt : strayStmts) {
                            allInstances.add(stmt.getSubject());
                        }
                        int writeCount = 0, skipCount = 0, strayRemoveCount = 0;
                        for (Resource inst : allInstances) {
                            // 移除 TDB2 中该 head 属性的所有旧值
                            namedModel.removeAll(inst, headProp, null);
                            boolean isTarget = targetInstances.contains(inst);
                            if (!isTarget) {
                                // 杂散实例（如 LLM 把 voltage_status_daily 写到了 StationMonthly）→ 只删不写
                                strayRemoveCount++;
                                continue;
                            }
                            // 从 baseModel 收集该 head 属性的值并去重后写入
                            java.util.List<Statement> vals = baseModel.listStatements(inst, headProp, (RDFNode) null).toList();
                            if (vals.isEmpty()) { skipCount++; continue; }
                            writeCount++;
                            Statement best = null;
                            // 尝试数值去重：保留最大值
                            double bestNum = Double.NEGATIVE_INFINITY;
                            boolean isNumeric = true;
                            for (Statement s : vals) {
                                if (!s.getObject().isLiteral()) { isNumeric = false; break; }
                                try {
                                    double v = Double.parseDouble(s.getObject().asLiteral().getLexicalForm());
                                    if (v > bestNum) { bestNum = v; best = s; }
                                } catch (NumberFormatException ex) { isNumeric = false; break; }
                            }
                            if (!isNumeric) {
                                // 字符串去重：按规则顺序选优先级最高的值
                                int bestOrder = Integer.MAX_VALUE;
                                for (Statement s : vals) {
                                    if (!s.getObject().isLiteral()) continue;
                                    String lexical = s.getObject().asLiteral().getLexicalForm();
                                    String key = headPropUri + "|" + lexical;
                                    int order = ruleOrder.getOrDefault(key, Integer.MAX_VALUE);
                                    // 使用 <= 而非 <，确保默认值（不在 ruleOrder 中，order=MAX_VALUE）也能被选中
                                    if (order <= bestOrder) { bestOrder = order; best = s; }
                                }
                            }
                            if (best != null) {
                                namedModel.add(best);
                            }
                            // 对于 mixed 类型的 head 属性（如 predicted_result 同时有 URI+字符串），
                            // 写入所有非字面量值（URI），避免 A胜/B胜 的 Team URI 被去重丢弃
                            for (Statement s : vals) {
                                if (!s.getObject().isLiteral()) {
                                    namedModel.add(s);
                                }
                            }
                        }
                        log.info("[Jena-SWRL] TDB2 head属性写入: {} → {} 个已写, {} 个跳过(无值), {} 个杂散移除",
                                headPropUri, writeCount, skipCount, strayRemoveCount);
                    }
                    // 写入非 head 属性的推断三元组（head 属性已从 baseModel 写入）
                    Model inferredNonHead = ModelFactory.createDefaultModel();
                    for (Statement stmt : inferredModel.listStatements().toList()) {
                        if (!allCompHeadProps.contains(stmt.getPredicate().getURI())) {
                            inferredNonHead.add(stmt);
                        }
                    }
                    namedModel.add(inferredNonHead);
                    tdb2Dataset.commit();
                } finally {
                    tdb2Dataset.end();
                }
            }
        }

        // BC 规则注册到 OxiRS（不物化），需同时启用 BC 查询拦截
        if (classification.hasBcRules() && bcEnabled && oxirsClient != null && oxirsClient.isEnabled()) {
            try {
                // 先移除该 ontologyId 下旧版本的规则，避免 OxiRS 积累脏规则
                java.util.List<String> oldIds = registeredRuleIds.remove(ontologyId);
                if (oldIds != null && !oldIds.isEmpty()) {
                    try {
                        oxirsClient.removeRules(oldIds, ontologyId);
                        log.info("[Jena-SWRL] 已从 OxiRS 移除旧版 {} 条规则", oldIds.size());
                    } catch (Exception re) {
                        log.warn("[Jena-SWRL] 移除旧规则失败（可能 OxiRS 已重启）: {}", re.getMessage());
                    }
                }
                List<com.ontologybuilder.oxirs.grpc.Rule> bcGrpcRules = convertProfilesToGrpcRules(
                        classification.getBcRules(), baseModel);
                oxirsClient.registerRules(bcGrpcRules, ontologyId);
                // 缓存新注册的规则 ID
                registeredRuleIds.put(ontologyId, bcGrpcRules.stream()
                        .map(com.ontologybuilder.oxirs.grpc.Rule::getId).collect(java.util.stream.Collectors.toList()));
                log.info("[Jena-SWRL] 已注册 {} 条 BC 规则到 OxiRS", bcGrpcRules.size());
            } catch (Exception e) {
                log.warn("[Jena-SWRL] BC 规则注册到 OxiRS 失败，降级为 FC 执行: {}",
                        e.getMessage());
                // 降级：对 BC 规则也执行 FC
                newTripleCount += materializeFallbackRules(classification.getBcRules(), baseModel,
                        ontologyId);
            }
        } else if (classification.hasBcRules()) {
            log.info("[Jena-SWRL] OxiRS 未启用，{} 条 BC 规则降级为 FC 执行",
                    classification.getBcRules().size());
            newTripleCount += materializeFallbackRules(classification.getBcRules(), baseModel,
                    ontologyId);
        }

        long elapsed = System.currentTimeMillis() - start;
        log.info("[Jena-SWRL] SWRL 规则物化完成: ontologyId={}, 推断三元组数={}, FC={}, BC={}, 耗时={}ms",
                ontologyId, newTripleCount, fcJenaRules.size(),
                classification.getBcRules().size(), elapsed);
        if (logSink != null) logSink.accept("success",
                String.format("[SWRL] 规则物化完成: %d 条推断, 耗时 %dms", newTripleCount, elapsed));
        return newTripleCount;
    }

    /**
     * 降级执行 BC 规则的前向链物化（OxiRS 未启用或不可用时）
     */
    private long materializeFallbackRules(List<RuleProfile> bcProfiles, Model baseModel,
                                           String ontologyId) {
        List<Rule> jenaRules = new ArrayList<>();
        for (RuleProfile profile : bcProfiles) {
            try {
                String ruleStr = buildJenaRuleFromProfile(profile, baseModel);
                Rule rule = Rule.parseRule(ruleStr);
                jenaRules.add(rule);
            } catch (Exception e) {
                log.warn("[Jena-SWRL] 降级规则转换失败: name={}, error={}",
                        profile.getRuleName(), e.getMessage());
            }
        }
        if (jenaRules.isEmpty()) return 0;

        GenericRuleReasoner reasoner = new GenericRuleReasoner(jenaRules);
        reasoner.setMode(GenericRuleReasoner.FORWARD);
        InfModel infModel = ModelFactory.createInfModel(reasoner, baseModel);
        infModel.prepare();
        Model newTriples = infModel.difference(baseModel);
        int count = (int) newTriples.size();
        if (count > 0) {
            tdb2Dataset.begin(ReadWrite.WRITE);
            try {
                tdb2Dataset.getNamedModel(ontologyId).add(newTriples);
                tdb2Dataset.commit();
            } finally {
                tdb2Dataset.end();
            }
        }
        return count;
    }

    private String buildJenaRuleFromProfile(RuleProfile profile, Model model) {
        // 必须用 model.getResource() 而非 ResourceFactory.createResource()，否则 getProperty() 抛 HasNoModelException
        Resource swrlImp = model.getResource(profile.getRuleId());
        String ruleName = sanitizeRuleName(profile.getRuleName());
        Resource swrlImpType = ResourceFactory.createResource(SWRL_NS + "Imp");
        Property swrlBody = ResourceFactory.createProperty(SWRL_NS + "body");
        Property swrlHead = ResourceFactory.createProperty(SWRL_NS + "head");
        Property rdfFirst = ResourceFactory.createProperty(
                "http://www.w3.org/1999/02/22-rdf-syntax-ns#first");
        Property rdfRest = ResourceFactory.createProperty(
                "http://www.w3.org/1999/02/22-rdf-syntax-ns#rest");
        Resource rdfNil = ResourceFactory.createResource(
                "http://www.w3.org/1999/02/22-rdf-syntax-ns#nil");

        Resource classAtomType = ResourceFactory.createResource(SWRL_NS + "ClassAtom");
        Resource dataPropAtomType = ResourceFactory.createResource(SWRL_NS + "DatavaluedPropertyAtom");
        Resource indPropAtomType = ResourceFactory.createResource(SWRL_NS + "IndividualPropertyAtom");
        Resource objPropAtomType = ResourceFactory.createResource(SWRL_NS + "ObjectPropertyAtom");
        Resource builtinAtomType = ResourceFactory.createResource(SWRL_NS + "BuiltinAtom");
        Property classPred = ResourceFactory.createProperty(SWRL_NS + "classPredicate");
        Property propertyPred = ResourceFactory.createProperty(SWRL_NS + "propertyPredicate");
        Property builtinAttr = ResourceFactory.createProperty(SWRL_NS + "builtin");
        Resource variableType = ResourceFactory.createResource(SWRL_NS + "Variable");
        Property arg1 = ResourceFactory.createProperty(SWRL_NS + "argument1");
        Property arg2 = ResourceFactory.createProperty(SWRL_NS + "argument2");
        Property argumentsProp = ResourceFactory.createProperty(SWRL_NS + "arguments");

        List<Resource> bodyAtoms = traverseRdfList(model, swrlImp.getProperty(swrlBody),
                rdfFirst, rdfRest, rdfNil);
        List<Resource> headAtoms = traverseRdfList(model, swrlImp.getProperty(swrlHead),
                rdfFirst, rdfRest, rdfNil);

        List<String> bodyClauses = new ArrayList<>();
        for (Resource atom : bodyAtoms) {
            java.util.List<String> clauses = convertAtomToJenaClause(model, atom, classAtomType,
                    dataPropAtomType, indPropAtomType, objPropAtomType, builtinAtomType,
                    variableType, classPred, propertyPred, arg1, arg2,
                    builtinAttr, argumentsProp, rdfFirst, rdfRest, rdfNil, false);
            if (clauses != null) bodyClauses.addAll(clauses);
        }
        List<String> headClauses = new ArrayList<>();
        for (Resource atom : headAtoms) {
            java.util.List<String> clauses = convertAtomToJenaClause(model, atom, classAtomType,
                    dataPropAtomType, indPropAtomType, objPropAtomType, builtinAtomType,
                    variableType, classPred, propertyPred, arg1, arg2,
                    builtinAttr, argumentsProp, rdfFirst, rdfRest, rdfNil, false);
            if (clauses != null) headClauses.addAll(clauses);
        }
        return "[" + ruleName + ": " + String.join(" ", bodyClauses)
                + " -> " + String.join(" ", headClauses) + "]";
    }

    private List<com.ontologybuilder.oxirs.grpc.Rule> convertProfilesToGrpcRules(
            List<RuleProfile> profiles, Model model) {
        List<com.ontologybuilder.oxirs.grpc.Rule> result = new ArrayList<>();
        for (RuleProfile profile : profiles) {
            String jenaRuleText = buildJenaRuleFromProfile(profile, model);
            result.add(com.ontologybuilder.oxirs.grpc.Rule.newBuilder()
                    .setId(profile.getRuleId())
                    .setName(profile.getRuleName())
                    .setSwrlText(profile.getSwrlText())
                    .setJenaRuleText(jenaRuleText)
                    .setClassification(profile.isFC()
                            ? com.ontologybuilder.oxirs.grpc.RuleClassification.FC
                            : com.ontologybuilder.oxirs.grpc.RuleClassification.BC)
                    .build());
        }
        return result;
    }

    /**
     * 获取指定本体的 FC (Forward-Chaining) gRPC 规则列表，用于 OxiRS 增量推理。
     * 返回空列表表示该本体没有 FC 规则或分类失败，增量推理将跳过。
     */
    private List<com.ontologybuilder.oxirs.grpc.Rule> getFcGrpcRules(String ontologyId) {
        if (oxirsClient == null || !oxirsClient.isEnabled()) {
            return Collections.emptyList();
        }
        try {
            tdb2Dataset.begin(ReadWrite.READ);
            Model tdb2Model;
            try {
                tdb2Model = tdb2Dataset.getNamedModel(ontologyId);
                if (tdb2Model.isEmpty()) {
                    return Collections.emptyList();
                }
                Model baseModel = ModelFactory.createDefaultModel();
                baseModel.add(tdb2Model);
                SwrlRuleClassifier.ClassificationResult classification = swrlClassifier.classify(baseModel);
                if (classification.getFcRules().isEmpty()) {
                    return Collections.emptyList();
                }
                return convertProfilesToGrpcRules(classification.getFcRules(), baseModel);
            } finally {
                tdb2Dataset.end();
            }
        } catch (Exception e) {
            log.warn("[Incremental] 获取 FC 规则失败: {}", e.getMessage());
            return Collections.emptyList();
        }
    }

    /**
     * 将 TDB2 Model 中的 SWRL 规则（swrl:Imp）转换为 Jena GenericRuleReasoner Rule 列表
     */
    private Map<String, Rule> convertSwrlToJenaRules(Model model) {
        Resource swrlImpType = ResourceFactory.createResource(SWRL_NS + "Imp");
        Resource classAtomType = ResourceFactory.createResource(SWRL_NS + "ClassAtom");
        Resource dataPropAtomType = ResourceFactory.createResource(SWRL_NS + "DatavaluedPropertyAtom");
        Resource indPropAtomType = ResourceFactory.createResource(SWRL_NS + "IndividualPropertyAtom");
        Resource objPropAtomType2 = ResourceFactory.createResource(SWRL_NS + "ObjectPropertyAtom");
        Resource builtinAtomType = ResourceFactory.createResource(SWRL_NS + "BuiltinAtom");
        Resource variableType = ResourceFactory.createResource(SWRL_NS + "Variable");

        Property swrlBody = ResourceFactory.createProperty(SWRL_NS + "body");
        Property swrlHead = ResourceFactory.createProperty(SWRL_NS + "head");
        Property classPred = ResourceFactory.createProperty(SWRL_NS + "classPredicate");
        Property propertyPred = ResourceFactory.createProperty(SWRL_NS + "propertyPredicate");
        Property arg1 = ResourceFactory.createProperty(SWRL_NS + "argument1");
        Property arg2 = ResourceFactory.createProperty(SWRL_NS + "argument2");
        Property builtinAttr = ResourceFactory.createProperty(SWRL_NS + "builtin");
        Property argumentsProp = ResourceFactory.createProperty(SWRL_NS + "arguments");

        String rdfNs = "http://www.w3.org/1999/02/22-rdf-syntax-ns#";
        Property rdfFirst = ResourceFactory.createProperty(rdfNs + "first");
        Property rdfRest = ResourceFactory.createProperty(rdfNs + "rest");
        Resource rdfNil = ResourceFactory.createResource(rdfNs + "nil");

        List<Resource> swrlImps = model.listResourcesWithProperty(RDF.type, swrlImpType).toList();
        Map<String, Rule> ruleByUri = new LinkedHashMap<>();

        for (Resource swrlImp : swrlImps) {
            try {
                String ruleName = sanitizeRuleName(extractSwrlRuleName(swrlImp));

                List<Resource> bodyAtoms = traverseRdfList(model, swrlImp.getProperty(swrlBody),
                        rdfFirst, rdfRest, rdfNil);
                List<Resource> headAtoms = traverseRdfList(model, swrlImp.getProperty(swrlHead),
                        rdfFirst, rdfRest, rdfNil);

                if (bodyAtoms.isEmpty() && headAtoms.isEmpty()) continue;

                List<String> bodyClauses = new ArrayList<>();
                List<String> headClauses = new ArrayList<>();

                // 重置临时变量计数器（每个 SWRL Imp 独立计数）
                tmpVarCounter.get().set(0);

                for (Resource atom : bodyAtoms) {
                    java.util.List<String> clauses = convertAtomToJenaClause(model, atom, classAtomType,
                            dataPropAtomType, indPropAtomType, objPropAtomType2, builtinAtomType, variableType,
                            classPred, propertyPred, arg1, arg2,
                            builtinAttr, argumentsProp, rdfFirst, rdfRest, rdfNil, true);
                    if (clauses != null) bodyClauses.addAll(clauses);
                }

                for (Resource atom : headAtoms) {
                    java.util.List<String> clauses = convertAtomToJenaClause(model, atom, classAtomType,
                            dataPropAtomType, indPropAtomType, objPropAtomType2, builtinAtomType, variableType,
                            classPred, propertyPred, arg1, arg2,
                            builtinAttr, argumentsProp, rdfFirst, rdfRest, rdfNil, true);
                    if (clauses != null) headClauses.addAll(clauses);
                }

                String bodyStr = String.join(" ", bodyClauses);
                String headStr = String.join(" ", headClauses);
                String ruleStr = "[" + ruleName + ": " + bodyStr + " -> " + headStr + "]";

                log.info("[Jena-SWRL] 转换规则: {}", ruleStr);
                Rule rule = Rule.parseRule(ruleStr);
                if (swrlImp.getURI() != null) {
                    ruleByUri.put(swrlImp.getURI(), rule);
                }
            } catch (Exception e) {
                log.warn("[Jena-SWRL] SWRL 规则转换失败: imp={}, error={}",
                        swrlImp.getURI(), e.getMessage());
            }
        }

        return ruleByUri;
    }

    /** 遍历 rdf:first/rdf:rest 链表，返回有序的资源列表 */
    private List<Resource> traverseRdfList(Model model, Statement listStmt,
                                           Property rdfFirst, Property rdfRest, Resource rdfNil) {
        List<Resource> items = new ArrayList<>();
        if (listStmt == null || !listStmt.getObject().isResource()) return items;

        Resource current = listStmt.getResource();
        Set<Resource> visited = new HashSet<>();
        while (current != null && !current.equals(rdfNil)) {
            if (!visited.add(current)) break;
            Statement firstStmt = current.getProperty(rdfFirst);
            if (firstStmt != null && firstStmt.getObject().isResource()) {
                items.add(firstStmt.getResource());
            }
            Statement restStmt = current.getProperty(rdfRest);
            if (restStmt != null && restStmt.getObject().isResource()) {
                current = restStmt.getResource();
            } else {
                break;
            }
        }
        return items;
    }

    /** 将单个 SWRL 原子转换为 Jena 规则子句（可返回多个，因多参数 builtin 会拆分为链式子句） */
    private java.util.List<String> convertAtomToJenaClause(Model model, Resource atom,
                                           Resource classAtomType, Resource dataPropAtomType,
                                           Resource indPropAtomType, Resource objPropAtomType,
                                           Resource builtinAtomType,
                                           Resource variableType,
                                           Property classPred, Property propertyPred,
                                           Property arg1, Property arg2,
                                           Property builtinAttr, Property argumentsProp,
                                           Property rdfFirst, Property rdfRest, Resource rdfNil,
                                           boolean reorderForJena) {
        Statement typeStmt = atom.getProperty(RDF.type);
        if (typeStmt == null || !typeStmt.getObject().isResource()) return null;
        String typeUri = typeStmt.getResource().getURI();

        if (typeUri.equals(classAtomType.getURI())) {
            String clsIri = getResourceIri(atom, classPred);
            String var = getSwrlArgVarName(model, atom, arg1, variableType);
            return (clsIri != null && var != null) ? List.of("(?" + var + " rdf:type <" + clsIri + ">)") : null;
        }

        if (typeUri.equals(indPropAtomType.getURI()) || typeUri.equals(objPropAtomType.getURI())) {
            String propIri = getResourceIri(atom, propertyPred);
            String v1 = getSwrlArgVarName(model, atom, arg1, variableType);
            String v2 = getSwrlArgAsValue(model, atom, arg2, variableType);
            return (propIri != null && v1 != null && v2 != null)
                    ? List.of("(?" + v1 + " <" + propIri + "> " + v2 + ")") : null;
        }

        if (typeUri.equals(dataPropAtomType.getURI())) {
            String propIri = getResourceIri(atom, propertyPred);
            String v1 = getSwrlArgVarName(model, atom, arg1, variableType);
            String v2 = getSwrlArgAsValue(model, atom, arg2, variableType);
            return (propIri != null && v1 != null && v2 != null)
                    ? List.of("(?" + v1 + " <" + propIri + "> " + v2 + ")") : null;
        }

        if (typeUri.equals(builtinAtomType.getURI())) {
            return convertBuiltinAtom(model, atom, builtinAttr, argumentsProp,
                    rdfFirst, rdfRest, rdfNil, variableType, reorderForJena);
        }

        return null;
    }

    /** 临时变量计数器（每轮 SWRL→Jena 转换期间复用） */
    private static final ThreadLocal<java.util.concurrent.atomic.AtomicInteger> tmpVarCounter =
            ThreadLocal.withInitial(() -> new java.util.concurrent.atomic.AtomicInteger(0));

    /** 将 SWRL BuiltinAtom 转换为 Jena builtin 调用子句（可返回多个子句） */
    private java.util.List<String> convertBuiltinAtom(Model model, Resource atom,
                                      Property builtinAttr, Property argumentsProp,
                                      Property rdfFirst, Property rdfRest, Resource rdfNil,
                                      Resource variableType, boolean reorderForJena) {
        Statement builtinStmt = atom.getProperty(builtinAttr);
        if (builtinStmt == null || !builtinStmt.getObject().isResource()) return List.of();
        String builtinUri = builtinStmt.getResource().getURI();

        // 对于 OxiRS 路径（reorderForJena=false），使用原始 SWRL builtin 名（如 abs, concat）
        // OxiRS 原生支持所有标准 SWRL builtin，不需要 Jena 自定义名
        String jenaBuiltin;
        if (!reorderForJena) {
            jenaBuiltin = builtinUri.contains("#") ? builtinUri.substring(builtinUri.lastIndexOf('#') + 1) : builtinUri;
        } else {
            jenaBuiltin = mapSwrlBuiltin(builtinUri);
        }
        if (jenaBuiltin == null) {
            log.warn("[Jena-SWRL] 不支持的 SWRL builtin: {}", builtinUri);
            return List.of();
        }

        // 遍历 swrl:arguments RDF 列表获取参数
        List<String> argStrs = new ArrayList<>();
        Statement argsListStmt = atom.getProperty(argumentsProp);
        if (argsListStmt != null && argsListStmt.getObject().isResource()) {
            Resource current = argsListStmt.getResource();
            Set<Resource> visited = new HashSet<>();
            while (current != null && !current.equals(rdfNil)) {
                if (!visited.add(current)) break;
                Statement firstStmt = current.getProperty(rdfFirst);
                if (firstStmt != null) {
                    String arg = rdfNodeToJenaValue(firstStmt.getObject(), model, variableType);
                    if (arg != null) argStrs.add(arg);
                }
                Statement restStmt = current.getProperty(rdfRest);
                if (restStmt != null && restStmt.getObject().isResource()) {
                    current = restStmt.getResource();
                } else {
                    break;
                }
            }
        }

        // SWRL builtins: result FIRST. Jena builtins: result LAST.
        // Only reorder for Jena path; OxiRS expects SWRL order.
        if (reorderForJena && argStrs.size() >= 2) {
            String swrlBuiltinLocal = builtinUri.substring(builtinUri.lastIndexOf('#') + 1);
            if (swrlBuiltinLocal.equals("concat")) {
                // SWRL concat: (arg1, arg2, ..., result) → Jena strConcat: (result, arg1, arg2, ...)
                String resultVar = argStrs.remove(argStrs.size() - 1);
                argStrs.add(0, resultVar);
            } else if (swrlBuiltinLocal.equals("add") || swrlBuiltinLocal.equals("multiply")) {
                // SWRL: result is args[0], move it to the end (Jena convention)
                List<String> reordered = new ArrayList<>(argStrs.subList(1, argStrs.size()));
                reordered.add(argStrs.get(0));
                argStrs = reordered;
                // Jena sum/product builtin 只接受 3 个参数。
                // 多参数（>3）需拆分为链式 3 参数调用：
                //   sum(a,b,c,d,result) → sum(a,b,__t0) sum(__t0,c,__t1) sum(__t1,d,result)
                if (argStrs.size() > 3) {
                    java.util.List<String> clauses = new java.util.ArrayList<>();
                    String prevTmp = argStrs.get(0); // first input
                    int counter = tmpVarCounter.get().getAndIncrement();
                    for (int i = 1; i < argStrs.size() - 1; i++) {
                        String tmpVar = (i == argStrs.size() - 2)
                                ? argStrs.get(argStrs.size() - 1) // last one → actual result var
                                : ("?__swrl_t" + counter + "_" + i);
                        clauses.add(jenaBuiltin + "(" + prevTmp + ", " + argStrs.get(i) + ", " + tmpVar + ")");
                        prevTmp = tmpVar;
                    }
                    return clauses;
                }
            } else if (argStrs.size() == 3
                    && (swrlBuiltinLocal.equals("subtract") || swrlBuiltinLocal.equals("divide"))) {
                // Jena difference/quotient: args[2] = args[0] OP args[1]
                argStrs = List.of(argStrs.get(1), argStrs.get(2), argStrs.get(0));
            }
            // 注意：comparison builtins (equal, notEqual, greaterThan, lessThan, ge, le)
            // 在 SWRL 和 Jena 中参数顺序一致（都是 input, input），无需 reorder
        }

        return List.of(jenaBuiltin + "(" + String.join(", ", argStrs) + ")");
    }

    /** SWRL builtin IRI → Jena builtin 名称映射 */
    private String mapSwrlBuiltin(String builtinUri) {
        // 内置数学/比较 builtins
        if (builtinUri.equals(SWRLB_NS + "greaterThan")) return "greaterThan";
        if (builtinUri.equals(SWRLB_NS + "lessThan")) return "lessThan";
        if (builtinUri.equals(SWRLB_NS + "lessThanOrEqual")) return "le";
        if (builtinUri.equals(SWRLB_NS + "greaterThanOrEqual")) return "ge";
        if (builtinUri.equals(SWRLB_NS + "equal")) return "equal";
        if (builtinUri.equals(SWRLB_NS + "notEqual")) return "notEqual";
        if (builtinUri.equals(SWRLB_NS + "add")) return "sum";
        if (builtinUri.equals(SWRLB_NS + "subtract")) return "difference";
        if (builtinUri.equals(SWRLB_NS + "multiply")) return "product";
        if (builtinUri.equals(SWRLB_NS + "divide")) return "quotient";
        // 字符串 builtins（OxiRS 原生支持，Jena 需自定义 Builtin 或参数重排）
        if (builtinUri.equals(SWRLB_NS + "stringLength")) return "stringLength";
        if (builtinUri.equals(SWRLB_NS + "stringEqualIgnoreCase")) return "stringEqualIgnoreCase";
        if (builtinUri.equals(SWRLB_NS + "bound")) return "swrlBound";
        if (builtinUri.equals(SWRLB_NS + "concat")) return "strConcat";
        if (builtinUri.equals(SWRLB_NS + "contains")) return "swrlStrContains";
        if (builtinUri.equals(SWRLB_NS + "abs")) return "swrlAbs";
        if (builtinUri.equals(SWRLB_NS + "not")) return "swrlNot";
        return null;
    }

    /** 获取资源的 IRI 字符串 */
    private String getResourceIri(Resource subject, Property predicate) {
        Statement stmt = subject.getProperty(predicate);
        if (stmt != null && stmt.getObject().isResource()) {
            return stmt.getResource().getURI();
        }
        return null;
    }

    /** 获取 SWRL 参数作为 Jena 变量名（如 "bill"）*/
    private String getSwrlArgVarName(Model model, Resource subject, Property argProp,
                                     Resource variableType) {
        Statement stmt = subject.getProperty(argProp);
        if (stmt == null || !stmt.getObject().isResource()) return null;
        Resource argRes = stmt.getResource();
        // 检查是否为变量
        if (model.contains(argRes, RDF.type, variableType)) {
            String local = getLocalName(argRes.getURI());
            return sanitizeVarName(local != null ? local : argRes.getId().getLabelString());
        }
        // 匿名变量（空白节点）
        if (argRes.isAnon()) {
            String label = argRes.getId().getLabelString();
            return label != null ? sanitizeVarName(label) : "anon";
        }
        // 作为变量处理
        String local = getLocalName(argRes.getURI());
        return sanitizeVarName(local != null ? local : "v");
    }

    /** 获取 SWRL 参数作为 Jena 值（变量或字面量）*/
    private String getSwrlArgAsValue(Model model, Resource subject, Property argProp,
                                     Resource variableType) {
        Statement stmt = subject.getProperty(argProp);
        if (stmt == null) return null;
        RDFNode node = stmt.getObject();
        return rdfNodeToJenaValue(node, model, variableType);
    }

    /** 将 RDFNode 转换为 Jena 规则中的值表示 */
    private String rdfNodeToJenaValue(RDFNode node, Model model, Resource variableType) {
        if (node.isLiteral()) {
            Literal lit = node.asLiteral();
            String dtUri = lit.getDatatypeURI();
            String lex = lit.getString()
                    .replace("\\", "\\\\")
                    .replace("\"", "\\\"");
            // 数值类型 → "VAL"^^xsd:TYPE（Jena GenericRuleReasoner 和 OxiRS 均可处理）
            if (dtUri != null && isNumericDatatype(dtUri)) {
                String xsdLocal = dtUri.substring(dtUri.lastIndexOf('#') + 1);
                return "\"" + lex + "\"^^xsd:" + xsdLocal;
            }
            return "\"" + lex + "\"";
        }
        if (node.isResource()) {
            Resource res = node.asResource();
            // 变量
            if (model.contains(res, RDF.type, variableType)) {
                String local = getLocalName(res.getURI());
                return "?" + sanitizeVarName(local != null ? local : res.getId().getLabelString());
            }
            // 匿名变量
            if (res.isAnon()) {
                String label = res.getId().getLabelString();
                return "?" + (label != null ? sanitizeVarName(label) : "anon");
            }
            // 变量：URI 中包含 /variable# 或 /Variable# 模式（部分 TTL 序列化省略了 rdf:type swrl:Variable）
            String uri = res.getURI();
            if (uri != null) {
                String local = getLocalName(uri);
                if (local != null && (uri.contains("/variable#") || uri.contains("/Variable#"))) {
                    return "?" + sanitizeVarName(local);
                }
                // 解析 rdf:value 间接引用（如 :Arg_X_bodyY_Z a rdf:Statement ; rdf:value "90"^^xsd:string）
                // SWRL builtin 常量参数可能被 LLM 编码为包装资源，需提取真实字面量值
                if (res.hasProperty(RDF.value)) {
                    RDFNode valNode = res.getProperty(RDF.value).getObject();
                    return rdfNodeToJenaValue(valNode, model, variableType);
                }
            }
            // 命名资源 → IRI 引用
            return "<" + res.getURI() + ">";
        }
        return null;
    }

    /** 判断是否为数值型 XSD 数据类型 */
    private boolean isNumericDatatype(String dtUri) {
        String xsd = "http://www.w3.org/2001/XMLSchema#";
        return dtUri.equals(xsd + "integer") || dtUri.equals(xsd + "int")
                || dtUri.equals(xsd + "long") || dtUri.equals(xsd + "short")
                || dtUri.equals(xsd + "decimal") || dtUri.equals(xsd + "float")
                || dtUri.equals(xsd + "double");
    }

    /** 从 IRI 提取本地名 */
    private String getLocalName(String uri) {
        if (uri == null) return null;
        int hash = uri.lastIndexOf('#');
        int slash = uri.lastIndexOf('/');
        int sep = Math.max(hash, slash);
        return sep >= 0 && sep < uri.length() - 1 ? uri.substring(sep + 1) : uri;
    }

    /** 清理 SWRL 规则名（去除前缀） */
    private String sanitizeRuleName(String name) {
        if (name == null || name.isBlank()) return "rule";
        if (name.startsWith("Var_")) name = name.substring(4);
        return name.replaceAll("[^a-zA-Z0-9_]", "_");
    }

    /** 清理变量名（确保 Jena 规则兼容） */
    private String sanitizeVarName(String name) {
        if (name == null || name.isBlank()) return "v";
        if (name.startsWith("Var_")) name = name.substring(4);
        return name.replaceAll("[^a-zA-Z0-9_]", "_");
    }

    /** 从 SWRL Imp 资源提取规则名称 */
    private String extractSwrlRuleName(Resource swrlImp) {
        Statement labelStmt = swrlImp.getProperty(ResourceFactory.createProperty(
                "http://www.w3.org/2000/01/rdf-schema#label"));
        if (labelStmt != null && labelStmt.getObject().isLiteral()) {
            return labelStmt.getObject().asLiteral().getString();
        }
        Statement commentStmt = swrlImp.getProperty(ResourceFactory.createProperty(
                "http://www.w3.org/2000/01/rdf-schema#comment"));
        if (commentStmt != null && commentStmt.getObject().isLiteral()) {
            return commentStmt.getObject().asLiteral().getString();
        }
        return getLocalName(swrlImp.getURI());
    }

    /** 从 Jena Rule 的 head(ispHead=true) 或 body(ispHead=false) 中提取属性 IRI */
    private java.util.Set<String> extractPropertyRefs(Rule rule, boolean isHead) {
        java.util.Set<String> props = new java.util.LinkedHashSet<>();
        // 使用 rule.toString() 而非 getHead().toString()，因为后者对 ClauseEntry[] 返回 Java 数组引用
        String ruleStr = rule.toString();
        int arrowIdx = ruleStr.indexOf(" -> ");
        String text;
        if (isHead && arrowIdx > 0) {
            text = ruleStr.substring(arrowIdx + 4);  // head 部分（" -> " 之后）
        } else if (!isHead && arrowIdx > 0) {
            text = ruleStr.substring(0, arrowIdx);   // body 部分（" -> " 之前）
        } else {
            text = ruleStr;  // 无箭头时使用整条规则文本
        }
        // 匹配 (?var <propUri> ?var) 和 (?var <propUri> "literal"/'literal') 形式
        // Jena Rule.toString() 对字面量可能用双引号或单引号，需同时兼容
        java.util.regex.Matcher m = java.util.regex.Pattern.compile(
                "\\(\\?\\w+ <([^>]+)> (\\?\\w+|['\"][^'\"]*['\"](?:\\^\\^[^)]+)?|[0-9]+\\.?[0-9]*)\\)").matcher(text);
        while (m.find()) props.add(m.group(1));
        return props;
    }

    /** 判断规则是否自引用：head 中至少有一个属性也出现在 body 中。
     *  自引用规则（如 sum(?cur, 5, ?new) → 同一个属性）在前向链式推理中会被反复触发，
     *  导致事实爆炸而非收敛，需要在降级路径中单独处理。 */
    private boolean isSelfReferencingRule(Rule rule) {
        java.util.Set<String> headProps = extractPropertyRefs(rule, true);
        java.util.Set<String> bodyProps = extractPropertyRefs(rule, false);
        for (String hp : headProps) {
            if (bodyProps.contains(hp)) return true;
        }
        return false;
    }

    /** 从规则 body 中提取所有数据/对象属性 IRI */
    private java.util.List<String> extractBodyPropsFromSumRule(Rule rule) {
        java.util.List<String> props = new java.util.ArrayList<>();
        String ruleStr = rule.toString();
        int arrowIdx = ruleStr.indexOf(" -> ");
        String body = arrowIdx > 0 ? ruleStr.substring(0, arrowIdx) : ruleStr;
        java.util.regex.Matcher m = java.util.regex.Pattern.compile(
                "\\(\\?\\w+ <([^>]+)> \\?\\w+\\)").matcher(body);
        while (m.find()) {
            String uri = m.group(1);
            if (!uri.equals("http://www.w3.org/1999/02/22-rdf-syntax-ns#type") && !props.contains(uri))
                props.add(uri);
        }
        return props;
    }

    /** 从规则 body 中提取所有 rdf:type 目标类 IRI */
    private java.util.List<String> extractAllTargetClassesFromRule(Rule rule) {
        java.util.List<String> classes = new java.util.ArrayList<>();
        String ruleStr = rule.toString();
        java.util.regex.Matcher m = java.util.regex.Pattern.compile(
                "\\(\\?\\w+ rdf:type <([^>]+)>\\)").matcher(ruleStr);
        while (m.find()) {
            String clsUri = m.group(1);
            if (!classes.contains(clsUri)) classes.add(clsUri);
        }
        return classes;
    }

    // ==================== Jena SWRL 方法结束 ====================

    /**
     * 解析 SPARQL 查询结果行的公共逻辑。
     */
    private List<Map<String, String>> collectSparqlResultRows(ResultSet results) {
        List<String> variables = results.getResultVars();
        List<Map<String, String>> resultList = new ArrayList<>();
        while (results.hasNext()) {
            QuerySolution solution = results.next();
            Map<String, String> row = new HashMap<>();
            for (String var : variables) {
                org.apache.jena.rdf.model.RDFNode node = solution.get(var);
                if (node == null) {
                    row.put(var, null);
                } else if (node.isURIResource()) {
                    row.put(var, node.asResource().getURI());
                } else if (node.isLiteral()) {
                    row.put(var, node.asLiteral().getString());
                } else if (node.isAnon()) {
                    row.put(var, node.asResource().getId().getLabelString());
                } else {
                    row.put(var, node.toString());
                }
            }
            resultList.add(row);
        }
        return resultList;
    }

    /**
     * 构建成功 SPARQL 结果。
     */
    private SparqlQueryResult buildSparqlSuccessResult(ResultSet results) {
        List<Map<String, String>> resultList = collectSparqlResultRows(results);
        return SparqlQueryResult.builder()
                .success(true)
                .variables(results.getResultVars())
                .results(resultList)
                .resultCount(resultList.size())
                .build();
    }

    /**
     * 执行 SPARQL 查询（基于 Dataset，保留命名图结构）
     * 相比旧版 executeSparqlQuery(Model, String)，此方法直接对 Dataset 执行查询，
     * SPARQL 中的 GRAPH <uri> 子句可正确解析命名图。
     */
    public SparqlQueryResult executeSparqlQuery(Dataset dataset, String sparqlQuery) {
        try {
            String enrichedQuery = ensureSparqlPrefixes(sparqlQuery);
            log.debug("执行SPARQL查询 (Dataset, 保留命名图): {}", enrichedQuery.substring(0, Math.min(200, enrichedQuery.length())));

            Query query = QueryFactory.create(enrichedQuery);
            try (QueryExecution qexec = QueryExecutionFactory.create(query, dataset)) {
                ResultSet results = qexec.execSelect();
                return buildSparqlSuccessResult(results);
            }
        } catch (Exception e) {
            log.error("SPARQL查询执行失败", e);
            return SparqlQueryResult.builder()
                    .success(false)
                    .errorMessage(e.getMessage())
                    .build();
        }
    }

    /**
     * 执行 SPARQL 查询（基于 Model，无命名图）
     * 注意：不保留 GRAPH 上下文，仅适用于 BC 内部或不需要 GRAPH 子句的场景。
     * 需要 GRAPH 子句的查询请使用 executeSparqlQuery(Dataset, String)。
     */
    public SparqlQueryResult executeSparqlQuery(Model model, String sparqlQuery) {
        try {
            String enrichedQuery = ensureSparqlPrefixes(sparqlQuery);
            log.debug("执行SPARQL查询 (Model, 无命名图): {}", enrichedQuery.substring(0, Math.min(200, enrichedQuery.length())));

            Query query = QueryFactory.create(enrichedQuery);
            try (QueryExecution qexec = QueryExecutionFactory.create(query, model)) {
                ResultSet results = qexec.execSelect();
                return buildSparqlSuccessResult(results);
            }
        } catch (Exception e) {
            log.error("SPARQL查询执行失败", e);
            return SparqlQueryResult.builder()
                    .success(false)
                    .errorMessage(e.getMessage())
                    .build();
        }
    }

    /**
     * 执行 SPARQL 查询（按 ontologyId 从 TDB2 查找模型）
     * 首次查询时自动触发 Pellet 推理物化
     */
    public SparqlQueryResult executeSparqlQuery(String ontologyId, String sparqlQuery) {
        // 首次查询时检查 TDB2 元数据，若已持久化物化标记则恢复，否则触发 Pellet 推理
        // 注意：如果之前物化已失败（如数据问题导致异常），跳过物化避免死循环重试
        if (!pelletMaterialized.containsKey(ontologyId)) {
            if (isPelletMaterializedInTdb2(ontologyId)) {
                pelletMaterialized.put(ontologyId, true);
                pelletMaterializeFailed.remove(ontologyId);
                log.info("[Pellet] 从 TDB2 元数据恢复物化状态: {}", ontologyId);
            } else if (pelletMaterializeFailed.containsKey(ontologyId)) {
                log.warn("[Pellet] 推理物化之前已失败 ({}), 跳过重试。前次错误: {}",
                        ontologyId, pelletMaterializeFailed.get(ontologyId));
            } else {
                try {
                    materializePelletInferences(ontologyId);
                } catch (Exception e) {
                    log.warn("[Pellet] 查询时推理物化失败 ({}), 将以无推理结果继续查询: {}",
                            ontologyId, e.getMessage());
                }
            }
        }

        tdb2Dataset.begin(ReadWrite.READ);
        try {
            Model model = tdb2Dataset.getNamedModel(ontologyId);
            if (model.isEmpty()) {
                return SparqlQueryResult.builder()
                        .success(false)
                        .errorMessage("本体未加载到 TDB2: " + ontologyId)
                        .build();
            }

            // 创建仅包含目标命名图的 Dataset，避免查询跨到其他本体实例
            Dataset scopedDs = DatasetFactory.create();
            scopedDs.setDefaultModel(model);
            scopedDs.addNamedModel(ontologyId, model);

            // BC 拦截: 检查 SPARQL 是否命中 BC 规则
            SparqlQueryInterceptor interceptor = interceptorCache.get(ontologyId);
            if (bcEnabled && oxirsClient != null && oxirsClient.isEnabled()
                    && interceptor != null && interceptor.hasBcPredicates()) {
                SparqlQueryInterceptor.InterceptionResult intercepted = interceptor.analyze(sparqlQuery);
                if (intercepted.hasBc()) {
                    return executeSparqlWithBC(scopedDs, model, sparqlQuery, ontologyId, intercepted);
                }
            }

            return executeSparqlQuery(scopedDs, sparqlQuery);
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            log.error("SPARQL查询执行失败", e);
            return SparqlQueryResult.builder()
                    .success(false)
                    .errorMessage("SPARQL查询失败: " + e.getMessage())
                    .build();
        } finally {
            tdb2Dataset.end();
        }
    }

    /**
     * 执行 SPARQL 查询并合并 OxiRS BC 推理结果。
     */
    private SparqlQueryResult executeSparqlWithBC(Dataset dataset, Model model, String sparqlQuery,
                                                   String ontologyId,
                                                   SparqlQueryInterceptor.InterceptionResult intercepted) {
        log.info("[BC-Query] BC 拦截命中: {} BC patterns, ontologyId={}",
                intercepted.getBcPatterns().size(), ontologyId);

        // TDB2 direct 查询使用 Dataset（保留命名图结构，支持 GRAPH 子句）
        SparqlQueryResult tdb2Result = executeSparqlQuery(dataset, sparqlQuery);

        List<Map<String, String>> bcResults = List.of();
        if (oxirsClient != null && oxirsClient.isEnabled()) {
            Map<String, List<RuleProfile>> predicateIndex = bcPredicateIndex.get(ontologyId);
            if (predicateIndex != null) {
                // 收集 BC 规则
                List<com.ontologybuilder.oxirs.grpc.Rule> bcRules = new ArrayList<>();
                for (SparqlQueryInterceptor.TriplePattern pattern : intercepted.getBcPatterns()) {
                    String pred = pattern.getPredicate();
                    if (pred != null && predicateIndex.containsKey(pred)) {
                        for (RuleProfile rp : predicateIndex.get(pred)) {
                            bcRules.add(com.ontologybuilder.oxirs.grpc.Rule.newBuilder()
                                    .setId(rp.getRuleId())
                                    .setName(rp.getRuleName())
                                    .setSwrlText(rp.getSwrlText())
                                    .setClassification(rp.isFC()
                            ? com.ontologybuilder.oxirs.grpc.RuleClassification.FC
                            : com.ontologybuilder.oxirs.grpc.RuleClassification.BC)
                                    .build());
                        }
                    }
                }

                if (!bcRules.isEmpty()) {
                    BcQueryExecutor bcExecutor = new BcQueryExecutor(oxirsClient, bcTimeoutMs, bcMaxDepth);
                    bcResults = bcExecutor.execute(intercepted.getBcPatterns(), model, bcRules);
                }
            }
        }

        // 合并结果
        if (bcResults.isEmpty()) {
            return tdb2Result;
        }
        return mergeResults(tdb2Result, bcResults);
    }

    private SparqlQueryResult mergeResults(SparqlQueryResult tdb2Result,
                                            List<Map<String, String>> bcResults) {
        if (!tdb2Result.isSuccess() && bcResults.isEmpty()) {
            return tdb2Result;
        }

        List<Map<String, String>> merged = new ArrayList<>(tdb2Result.getResults());
        // 简单去重合并
        for (Map<String, String> bcRow : bcResults) {
            if (!merged.contains(bcRow)) {
                merged.add(bcRow);
            }
        }

        return SparqlQueryResult.builder()
                .success(true)
                .variables(tdb2Result.getVariables())
                .results(merged)
                .resultCount(merged.size())
                .build();
    }

    /**
     * 执行多图联合 SPARQL 查询（跨多个 Jena 命名图）
     * 将多个命名图合并为 Union Model 后执行查询
     */
    public SparqlQueryResult executeMultiGraphSparqlQuery(List<String> modelNames, String sparqlQuery) {
        // 在事务外完成 Pellet 推理物化，避免嵌套事务导致死锁
        // 优先从 TDB2 元数据恢复，避免重复推理
        for (String modelName : modelNames) {
            if (!pelletMaterialized.containsKey(modelName)) {
                if (isPelletMaterializedInTdb2(modelName)) {
                    pelletMaterialized.put(modelName, true);
                    pelletMaterializeFailed.remove(modelName);
                    log.info("[Pellet] 从 TDB2 元数据恢复物化状态: {}", modelName);
                } else if (pelletMaterializeFailed.containsKey(modelName)) {
                    log.warn("[Pellet] 推理物化之前已失败 ({}), 跳过重试。前次错误: {}",
                            modelName, pelletMaterializeFailed.get(modelName));
                } else {
                    try {
                        materializePelletInferences(modelName);
                    } catch (Exception e) {
                        log.warn("[Pellet] 多图查询时推理物化失败 ({}), 将以无推理结果继续查询: {}",
                                modelName, e.getMessage());
                    }
                }
            }
        }
        tdb2Dataset.begin(ReadWrite.READ);
        try {
            // 构建 Dataset：defaultModel 合并多图（兼容无 GRAPH 查询），
            // 同时保留每个命名图（支持 GRAPH <uri> 查询）
            Dataset queryDataset = DatasetFactory.create();
            Model unionModel = ModelFactory.createDefaultModel();
            long totalNamedTriples = 0;
            for (String modelName : modelNames) {
                Model namedModel = tdb2Dataset.getNamedModel(modelName);
                long modelSize = (namedModel != null) ? namedModel.size() : -1;
                totalNamedTriples += (modelSize > 0) ? modelSize : 0;
                log.info("[多图SPARQL] modelName={}, exists={}, triples={}",
                        modelName, namedModel != null, modelSize);
                if (namedModel != null && !namedModel.isEmpty()) {
                    unionModel.add(namedModel);
                    queryDataset.addNamedModel(modelName, namedModel);
                }
            }
            queryDataset.setDefaultModel(unionModel);
            log.info("[多图SPARQL] 请求 {} 个图, 合并后 defaultModel 三元组数={}, 各图三元组合计={}",
                    modelNames.size(), unionModel.size(), totalNamedTriples);
            if (unionModel.isEmpty()) {
                return SparqlQueryResult.builder()
                        .success(false)
                        .errorMessage("所有指定本体均未加载到 TDB2")
                        .build();
            }
            return executeSparqlQuery(queryDataset, sparqlQuery);
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            log.error("多图SPARQL查询执行失败", e);
            return SparqlQueryResult.builder()
                    .success(false)
                    .errorMessage("多图SPARQL查询失败: " + e.getMessage())
                    .build();
        } finally {
            tdb2Dataset.end();
        }
    }

    /**
     * 导出一个命名图的 RDF 数据为 Turtle 格式字符串（供 Semantica 消费）
     */
    public String exportModelAsTurtle(String ontologyId, int limit) {
        tdb2Dataset.begin(ReadWrite.READ);
        try {
            Model model = tdb2Dataset.getNamedModel(ontologyId);
            if (model.isEmpty()) {
                return "# Jena TDB2 命名图为空: " + ontologyId + "\n";
            }
            String constructQuery = "CONSTRUCT { ?s ?p ?o } WHERE { ?s ?p ?o } LIMIT " + limit;
            Query query = QueryFactory.create(constructQuery);
            try (QueryExecution qexec = QueryExecutionFactory.create(query, model)) {
                Model resultModel = qexec.execConstruct();
                java.io.StringWriter sw = new java.io.StringWriter();
                resultModel.write(sw, "TURTLE");
                log.info("导出 Turtle: ontologyId={}, triples={}, chars={}",
                        ontologyId, resultModel.size(), sw.toString().length());
                return sw.toString();
            }
        } catch (Exception e) {
            log.error("导出 Turtle 失败: ontologyId={}", ontologyId, e);
            return "# 导出失败: " + e.getMessage() + "\n";
        } finally {
            tdb2Dataset.end();
        }
    }

    // ==================== Schema 拓扑图探测 ====================

    /**
     * 探测对象属性的实际 domain→range 连接（仅对有实例数据的属性）
     * 返回每个对象属性连接了哪些类对，以及三元组数量
     */
    public List<Map<String, Object>> probeObjectPropertyConnections(
            String modelName, List<String> objPropsWithData) {
        List<Map<String, Object>> connections = new ArrayList<>();
        if (objPropsWithData == null || objPropsWithData.isEmpty()) return connections;

        for (String propIri : objPropsWithData) {
            String sparql = String.format(
                "PREFIX rdf: <http://www.w3.org/1999/02/22-rdf-syntax-ns#>\n" +
                "SELECT DISTINCT ?domainClass ?rangeClass (COUNT(DISTINCT ?s) AS ?tripleCount)\n" +
                "WHERE {\n" +
                "  ?s a ?domainClass .\n" +
                "  ?s <%s> ?o .\n" +
                "  ?o a ?rangeClass .\n" +
                "  FILTER(?domainClass != <http://www.w3.org/2002/07/owl#NamedIndividual>)\n" +
                "  FILTER(?rangeClass != <http://www.w3.org/2002/07/owl#NamedIndividual>)\n" +
                "}\n" +
                "GROUP BY ?domainClass ?rangeClass\n" +
                "ORDER BY DESC(?tripleCount)\n" +
                "LIMIT 50",
                propIri);
            try {
                SparqlQueryResult result = executeSparqlQuery(modelName, sparql);
                if (result.isSuccess() && result.getResults() != null) {
                    for (Map<String, String> row : result.getResults()) {
                        Map<String, Object> conn = new LinkedHashMap<>();
                        conn.put("propIri", propIri);
                        conn.put("domainClass", row.get("domainClass"));
                        conn.put("rangeClass", row.get("rangeClass"));
                        conn.put("tripleCount", row.get("tripleCount") != null
                                ? Integer.parseInt(row.get("tripleCount")) : 0);
                        connections.add(conn);
                    }
                }
            } catch (Exception e) {
                log.debug("探测对象属性连接失败: propIri={}, error={}", propIri, e.getMessage());
            }
        }
        log.info("[SchemaGraph] 对象属性连接探测完成: {} 个属性 → {} 条连接",
                objPropsWithData.size(), connections.size());
        return connections;
    }

    /**
     * 探测标识属性的类共享情况。
     * 对每个业务数据属性，检测哪些类包含该属性的实例。
     * 如果多个类共享同一个标识属性（如 station_name），则它们是同一实体的不同视图。
     */
    public List<Map<String, Object>> probeIdentityOverlaps(
            String modelName, List<String> businessPredicates) {
        List<Map<String, Object>> overlaps = new ArrayList<>();
        if (businessPredicates == null || businessPredicates.isEmpty()) return overlaps;

        // 对每个业务谓词探测其 domain 类分布
        for (String propIri : businessPredicates) {
            String propLocal = propIri.contains("#")
                    ? propIri.substring(propIri.lastIndexOf('#') + 1)
                    : propIri.substring(propIri.lastIndexOf('/') + 1);
            // 只检查可能是标识属性的属性（名称、编号、代码类）
            String propLower = propLocal.toLowerCase();
            boolean isIdProp = propLower.contains("name") || propLower.contains("code")
                    || propLower.contains("id") || propLower.contains("名称")
                    || propLower.contains("编号") || propLower.contains("代码")
                    || propLower.contains("label") || propLower.contains("title");
            if (!isIdProp) continue;

            String sparql = String.format(
                "PREFIX rdf: <http://www.w3.org/1999/02/22-rdf-syntax-ns#>\n" +
                "SELECT ?class (COUNT(DISTINCT ?s) AS ?cnt)\n" +
                "WHERE {\n" +
                "  ?s a ?class .\n" +
                "  ?s <%s> ?val .\n" +
                "  FILTER(isLiteral(?val))\n" +
                "  FILTER(?class != <http://www.w3.org/2002/07/owl#NamedIndividual>)\n" +
                "}\n" +
                "GROUP BY ?class\n" +
                "HAVING (COUNT(?s) >= 1)",
                propIri);
            try {
                SparqlQueryResult result = executeSparqlQuery(modelName, sparql);
                if (result.isSuccess() && result.getResults() != null && result.getResults().size() >= 2) {
                    List<String> classes = new ArrayList<>();
                    for (Map<String, String> row : result.getResults()) {
                        String cls = row.get("class");
                        if (cls != null && !cls.isBlank()) classes.add(cls);
                    }
                    if (classes.size() >= 2) {
                        Map<String, Object> overlap = new LinkedHashMap<>();
                        overlap.put("propIri", propIri);
                        overlap.put("propLocal", propLocal);
                        overlap.put("classes", classes);
                        overlaps.add(overlap);
                    }
                }
            } catch (Exception e) {
                log.debug("探测标识属性重叠失败: propIri={}, error={}", propIri, e.getMessage());
            }
        }
        log.info("[SchemaGraph] 标识属性重叠探测完成: {} 个属性 → {} 个共享组",
                businessPredicates.size(), overlaps.size());
        return overlaps;
    }

    // ==================== SPARQL 前缀自动注入 (continued) ====================

    private static final Map<String, String> STANDARD_SPARQL_PREFIXES = new LinkedHashMap<>();
    static {
        STANDARD_SPARQL_PREFIXES.put("owl", "http://www.w3.org/2002/07/owl#");
        STANDARD_SPARQL_PREFIXES.put("rdf", "http://www.w3.org/1999/02/22-rdf-syntax-ns#");
        STANDARD_SPARQL_PREFIXES.put("rdfs", "http://www.w3.org/2000/01/rdf-schema#");
        STANDARD_SPARQL_PREFIXES.put("xsd", "http://www.w3.org/2001/XMLSchema#");
        STANDARD_SPARQL_PREFIXES.put("swrl", "http://www.w3.org/2003/11/swrl#");
        STANDARD_SPARQL_PREFIXES.put("swrlb", "http://www.w3.org/2003/11/swrlb#");
        STANDARD_SPARQL_PREFIXES.put("dc", "http://purl.org/dc/elements/1.1/");
        STANDARD_SPARQL_PREFIXES.put("dcterms", "http://purl.org/dc/terms/");
        STANDARD_SPARQL_PREFIXES.put("foaf", "http://xmlns.com/foaf/0.1/");
        STANDARD_SPARQL_PREFIXES.put("skos", "http://www.w3.org/2004/02/skos/core#");
    }

    private static final Pattern PREFIX_PATTERN = Pattern.compile("PREFIX\\s+(\\w+)\\s*:\\s*<[^>]+>", Pattern.CASE_INSENSITIVE);

    /**
     * 自动为 SPARQL 查询注入缺失的标准前缀声明。
     * 仅在查询中实际使用了对应前缀但未声明时才注入。
     */
    private String ensureSparqlPrefixes(String sparqlQuery) {
        // 收集已声明的前缀
        Set<String> declaredPrefixes = new HashSet<>();
        Matcher matcher = PREFIX_PATTERN.matcher(sparqlQuery);
        while (matcher.find()) {
            declaredPrefixes.add(matcher.group(1).toLowerCase());
        }

        // 找出查询中实际使用但未声明的前缀
        // 匹配查询正文中 word:localName 形式的前缀引用（排除已声明的）
        StringBuilder prefixBlock = new StringBuilder();
        for (Map.Entry<String, String> entry : STANDARD_SPARQL_PREFIXES.entrySet()) {
            String prefix = entry.getKey();
            if (!declaredPrefixes.contains(prefix.toLowerCase())) {
                // 检查查询正文（去掉已有PREFIX行）是否使用了该前缀
                String body = PREFIX_PATTERN.matcher(sparqlQuery).replaceAll("").trim();
                // 匹配 prefix: 后跟字母/下划线/数字，排除字符串内的
                if (body.matches("(?s).*\\b" + Pattern.quote(prefix) + "\\s*:\\s*\\w+.*")) {
                    prefixBlock.append("PREFIX ").append(prefix).append(": <").append(entry.getValue()).append(">\n");
                }
            }
        }

        if (prefixBlock.length() > 0) {
            log.debug("自动注入SPARQL前缀:\n{}", prefixBlock);
            return prefixBlock.toString() + "\n" + sparqlQuery;
        }
        return sparqlQuery;
    }

    // ==================== 结果 DTO ====================

    @Data
    @Builder
    public static class HermiTCheckResult {
        private boolean consistent;
        private List<String> inconsistencies;
        private List<String> unsatisfiableClasses;
        private Map<String, List<String>> inferredHierarchy;
        private Map<String, List<String>> inferredPropertyHierarchy;
        private int totalClasses;
        private int totalObjectProperties;
        private int totalDatatypeProperties;
        private int totalAxioms;
        private String reasonerName;
        private String reasonerVersion;
        private long preparationTimeMs;
        // 能力1增强: 本体一致性检测 — 等价类、互斥类
        private Map<String, List<String>> equivalentClasses;
        private Map<String, List<String>> disjointClasses;
        // 能力2增强: 类层级关系推理 — 推断超类
        private Map<String, List<String>> inferredSuperClasses;
        // 能力3: 实例归属判断
        private int totalIndividuals;
        private Map<String, List<String>> inferredIndividualTypes;
        private Map<String, List<String>> classInstances;
        // 能力4: 属性与关系推导
        private Map<String, List<String>> propertyCharacteristics;
        private Map<String, String> inverseProperties;
        private Map<String, List<String>> equivalentProperties;
        // 能力5: 数据属性推导
        private Map<String, List<String>> inferredDataPropertyHierarchy;
        private Map<String, List<String>> dataPropertyDomains;
        // 能力6: SWRL 规则推导
        private List<String> swrlRuleResults;
    }

    @Data
    @Builder
    public static class SparqlQueryResult {
        private boolean success;
        private List<String> variables;
        private List<Map<String, String>> results;
        private int resultCount;
        private String errorMessage;
    }

    // ==================== 辅助方法 ====================

    /**
     * 将短格式的 XSD 类型名（如 "xsd:string"）展开为完整 URI（如 "http://www.w3.org/2001/XMLSchema#string"）。
     * 如果输入已经是完整 URI 或无法识别的短格式，则原样返回。
     */
    private static String expandXsdTypeUri(String typeUri) {
        if (typeUri == null || typeUri.isBlank()) return typeUri;
        // 已经是完整 URI
        if (typeUri.startsWith("http://") || typeUri.startsWith("https://")) return typeUri;
        // 常见前缀映射
        Map<String, String> prefixMap = Map.of(
                "xsd:", "http://www.w3.org/2001/XMLSchema#",
                "owl:", "http://www.w3.org/2002/07/owl#",
                "rdf:", "http://www.w3.org/1999/02/22-rdf-syntax-ns#",
                "rdfs:", "http://www.w3.org/2000/01/rdf-schema#"
        );
        for (Map.Entry<String, String> entry : prefixMap.entrySet()) {
            if (typeUri.startsWith(entry.getKey())) {
                return entry.getValue() + typeUri.substring(entry.getKey().length());
            }
        }
        // 无前缀的常见 XSD 类型名（如 "string", "integer", "decimal" 等）
        Set<String> xsdTypes = Set.of("string", "integer", "int", "decimal", "float", "double",
                "boolean", "date", "dateTime", "time", "anyURI", "byte", "short", "long",
                "nonNegativeInteger", "positiveInteger", "nonPositiveInteger", "negativeInteger",
                "unsignedInt", "unsignedLong", "unsignedShort", "unsignedByte");
        String localName = typeUri.contains(":") ? typeUri.substring(typeUri.lastIndexOf(':') + 1) : typeUri;
        if (xsdTypes.contains(localName)) {
            return "http://www.w3.org/2001/XMLSchema#" + localName;
        }
        return typeUri;
    }

    // ==================== 自定义 Jena Builtins ====================

    /**
     * 实现 SWRL swrlb:bound 语义的 Jena Builtin。
     * SWRL 中 bound(?x) 检查变量是否已被绑定（赋值），
     * 在 Jena 前向链式推理中，所有 body 变量均由前序原子绑定，
     * 因此 bound(?x) 等价于检查 ?x 是否已解析为具体值（非变量节点）。
     */
    public static class SwrlBound extends BaseBuiltin {
        @Override
        public String getName() { return "swrlBound"; }

        @Override
        public boolean bodyCall(org.apache.jena.graph.Node[] args, int length, RuleContext context) {
            if (length < 1) return false;
            for (int i = 0; i < length; i++) {
                org.apache.jena.graph.Node n = getArg(i, args, context);
                if (n == null || n.isVariable()) return false;
            }
            return true;
        }
    }
}
