package com.ontologybuilder.ontology.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ontologybuilder.mdp.MdpCatalogNotifier;
import com.ontologybuilder.common.exception.NotFoundException;
import com.ontologybuilder.common.response.Result;
import com.ontologybuilder.common.util.TtlSyntaxFixer;
import com.ontologybuilder.instantiation.model.InstantiationTask;
import com.ontologybuilder.instantiation.model.OntologyInstance;
import com.ontologybuilder.instantiation.repository.InstantiationTaskRepository;
import com.ontologybuilder.instantiation.repository.OntologyInstanceRepository;
import com.ontologybuilder.rule.model.BusinessRule;
import com.ontologybuilder.rule.service.RuleService;
import com.ontologybuilder.rule.service.SwrlBuiltinCatalog;
import com.ontologybuilder.ontology.model.LinkType;
import com.ontologybuilder.ontology.model.ObjectType;
import com.ontologybuilder.ontology.model.Ontology;
import com.ontologybuilder.ontology.service.OntologyService;
import com.ontologybuilder.ontology.service.reasoning.ReasoningService;
import com.ontologybuilder.connector.model.FileUpload;
import com.ontologybuilder.connector.service.FileUploadService;
import com.ontologybuilder.connector.storage.StorageException;
import com.ontologybuilder.connector.storage.StorageStrategy;
import com.ontologybuilder.connector.storage.StorageStrategyFactory;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.ModelFactory;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.*;

import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import jakarta.annotation.PreDestroy;
import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.*;
import java.util.function.Function;
import java.util.stream.Collectors;

@Slf4j
@RestController
@RequestMapping("/api/v1/ontologies")
@RequiredArgsConstructor
@Tag(name = "本体管理", description = "本体增删改查、Markdown导入提取、TTL管理、实例化及SPARQL查询")
public class OntologyController {

    private final OntologyService ontologyService;
    private final MdpCatalogNotifier mdpCatalogNotifier;
    private final RuleService ruleService;
    private final ReasoningService reasoningService;
    private final InstantiationTaskRepository instantiationTaskRepository;
    private final OntologyInstanceRepository instanceRepository;
    private final FileUploadService fileUploadService;
    private final StorageStrategyFactory storageStrategyFactory;
    private final ObjectMapper objectMapper;

    private static final java.net.http.HttpClient SHARED_HTTP_CLIENT = java.net.http.HttpClient.newBuilder()
            .connectTimeout(java.time.Duration.ofSeconds(30))
            .followRedirects(java.net.http.HttpClient.Redirect.NORMAL)
            .version(java.net.http.HttpClient.Version.HTTP_1_1)  // 使用 HTTP/1.1 避免与 FastAPI/uvicorn 的 HTTP/2 兼容性问题
            .build();

    private final ExecutorService sseExecutor = new ThreadPoolExecutor(
            4, 32, 60L, TimeUnit.SECONDS,
            new LinkedBlockingQueue<>(256),
            r -> { Thread t = new Thread(r); t.setName("sse-onto-" + Thread.currentThread().getId()); t.setDaemon(true); return t; },
            new ThreadPoolExecutor.CallerRunsPolicy()
    );

    @PreDestroy
    public void destroy() {
        sseExecutor.shutdown();
        try { sseExecutor.awaitTermination(10, TimeUnit.SECONDS); }
        catch (InterruptedException e) { sseExecutor.shutdownNow(); }
    }

    @Value("${file.storage.local.base-dir:./storage}")
    private String storageBaseDir;

    @Value("${agent.base-url:http://localhost:8000}")
    private String agentServiceUrl;

    @PostMapping
    @Operation(summary = "创建本体")
    public Result<Ontology> create(@RequestBody Ontology ontology) {
        Ontology result = ontologyService.create(ontology);
        mdpCatalogNotifier.notifyRefresh();
        return Result.success(result);
    }

    @GetMapping
    @Operation(summary = "查询所有本体")
    public Result<List<Ontology>> findAll() {
        return Result.success(ontologyService.findAll());
    }

    @GetMapping("/swrl-count")
    @Operation(summary = "统计已实例化的SWRL规则数量")
    public Result<Long> getSwrlRuleCount() {
        return Result.success(reasoningService.countSwrlRules());
    }

    // 已知静态端点名称列表（用于排除 /{id} 的误匹配）
    private static final Set<String> KNOWN_ENDPOINTS = Set.of(
            "batch", "import-markdown", "import-markdown-stream", "import", "import-ttl", "save-version", "swrl-count"
    );

    @GetMapping("/{id}")
    @Operation(summary = "查询本体详情")
    public Result<Ontology> findById(@PathVariable String id) {
        // 防止静态端点名被当作 ID 处理（当 GET 请求误到达此端点时）
        if (KNOWN_ENDPOINTS.contains(id)) {
            throw new com.ontologybuilder.common.exception.NotFoundException(
                    "请使用 POST 方法访问此端点: /" + id);
        }
        return Result.success(ontologyService.findById(id));
    }

    @PutMapping("/{id}")
    @Operation(summary = "更新本体")
    public Result<Ontology> update(@PathVariable String id, @RequestBody Ontology ontology) {
        Ontology result = ontologyService.update(id, ontology);
        mdpCatalogNotifier.notifyRefresh();
        return Result.success(result);
    }

    @DeleteMapping("/{id}")
    @Operation(summary = "删除本体")
    public Result<Void> delete(@PathVariable String id) {
        ontologyService.delete(id);
        mdpCatalogNotifier.notifyRefresh();
        return Result.success();
    }

    @DeleteMapping("/batch")
    @Operation(summary = "批量删除本体")
    public Result<Map<String, Object>> batchDelete(@RequestBody List<String> ids) {
        Map<String, Object> result = ontologyService.batchDelete(ids);
        mdpCatalogNotifier.notifyRefresh();
        return Result.success(result);
    }

    @PutMapping("/{id}/rename")
    @Operation(summary = "重命名本体版本")
    public Result<Ontology> rename(@PathVariable String id, @RequestBody Map<String, String> body) {
        Ontology ontology = ontologyService.findById(id);
        String newName = body.get("name");
        String newVersionName = body.get("versionName");
        boolean changed = false;
        if (newName != null && !newName.isBlank()) {
            ontology.setName(newName);
            changed = true;
        }
        if (newVersionName != null && !newVersionName.isBlank()) {
            ontology.setVersionName(newVersionName);
            changed = true;
        }
        if (changed) {
            ontologyService.update(id, ontology);
        }
        return Result.success(ontology);
    }

    /**
     * 解析路径参数中的本体 ID。
     * 如果传入的是 jenaModelName（inst_{ontologyId}_v{version}），则提取出真正的 ontologyId；
     * 否则原样返回。
     */
    private String resolveOntologyId(String id) {
        if (id != null && id.startsWith("inst_")) {
            // 格式: inst_{ontologyId}_v{version}，去掉 inst_ 前缀
            String rest = id.substring(5);
            // 去掉 _v{version} 后缀（找最后一个 _v）
            int vIndex = rest.lastIndexOf("_v");
            if (vIndex > 0) {
                return rest.substring(0, vIndex);
            }
        }
        return id;
    }

    @GetMapping("/{id}/object-types")
    @Operation(summary = "查询对象类型")
    public Result<List<ObjectType>> findObjectTypes(@PathVariable String id) {
        return Result.success(ontologyService.findObjectTypes(resolveOntologyId(id)));
    }

    @PostMapping("/{id}/object-types")
    @Operation(summary = "创建对象类型")
    public Result<ObjectType> createObjectType(@PathVariable String id, @RequestBody ObjectType objectType) {
        ObjectType result = ontologyService.createObjectType(id, objectType);
        mdpCatalogNotifier.notifyRefresh();
        return Result.success(result);
    }

    @PutMapping("/{id}/object-types/{objectTypeId}")
    @Operation(summary = "更新对象类型")
    public Result<ObjectType> updateObjectType(@PathVariable String id, @PathVariable String objectTypeId,
            @RequestBody ObjectType objectType) {
        ObjectType result = ontologyService.updateObjectType(objectTypeId, objectType);
        mdpCatalogNotifier.notifyRefresh();
        return Result.success(result);
    }

    @DeleteMapping("/{id}/object-types/{objectTypeId}")
    @Operation(summary = "删除对象类型")
    public Result<Void> deleteObjectType(@PathVariable String id, @PathVariable String objectTypeId) {
        ontologyService.deleteObjectType(objectTypeId);
        mdpCatalogNotifier.notifyRefresh();
        return Result.success();
    }

    @PutMapping("/{id}/object-types/{objectTypeId}/position")
    @Operation(summary = "更新对象类型位置")
    public Result<ObjectType> updateObjectPosition(
            @PathVariable String id,
            @PathVariable String objectTypeId,
            @RequestBody Map<String, Double> position) {
        return Result.success(ontologyService.updateObjectPosition(objectTypeId, position));
    }

    @GetMapping("/{id}/link-types")
    @Operation(summary = "查询关联类型")
    public Result<List<LinkType>> findLinkTypes(@PathVariable String id) {
        return Result.success(ontologyService.findLinkTypes(resolveOntologyId(id)));
    }

    @PostMapping("/{id}/link-types")
    @Operation(summary = "创建关联类型")
    public Result<LinkType> createLinkType(@PathVariable String id, @RequestBody LinkType linkType) {
        LinkType result = ontologyService.createLinkType(id, linkType);
        mdpCatalogNotifier.notifyRefresh();
        return Result.success(result);
    }

    @PutMapping("/{id}/link-types/{linkTypeId}")
    @Operation(summary = "更新关联类型")
    public Result<LinkType> updateLinkType(@PathVariable String id, @PathVariable String linkTypeId,
            @RequestBody LinkType linkType) {
        LinkType result = ontologyService.updateLinkType(linkTypeId, linkType);
        mdpCatalogNotifier.notifyRefresh();
        return Result.success(result);
    }

    @DeleteMapping("/{id}/link-types/{linkTypeId}")
    @Operation(summary = "删除关联类型")
    public Result<Void> deleteLinkType(@PathVariable String id, @PathVariable String linkTypeId) {
        ontologyService.deleteLinkType(linkTypeId);
        mdpCatalogNotifier.notifyRefresh();
        return Result.success();
    }

    @GetMapping("/{id}/swrl-vocabulary")
    @Operation(summary = "获取 SWRL 规则构建词汇表",
               description = "聚合 DB 中的 ObjectType(类)、LinkType(对象属性)、ObjectType.propertiesJson(数据属性)，以及 SWRL builtins 目录，返回统一词汇表供前端 SWRL 规则构建器使用")
    public Result<Map<String, Object>> getSwrlVocabulary(@PathVariable String id) {
        String ontologyId = resolveOntologyId(id);

        // 1. 从 DB 获取 ObjectType（类）
        List<ObjectType> objectTypes = ontologyService.findObjectTypes(ontologyId);

        // 2. 从 DB 获取 LinkType（对象属性 + 部分数据属性）
        List<LinkType> linkTypes = ontologyService.findLinkTypes(ontologyId);

        // 3. 构建 classes 列表
        List<Map<String, String>> classes = new java.util.ArrayList<>();
        for (ObjectType ot : objectTypes) {
            Map<String, String> cls = new java.util.LinkedHashMap<>();
            cls.put("id", ot.getId());
            cls.put("iri", ot.getIri());
            cls.put("label", ot.getLabel());
            cls.put("shortName", getShortName(ot.getIri(), ot.getLabel()));
            if (ot.getDescription() != null && !ot.getDescription().isBlank()) {
                cls.put("description", ot.getDescription());
            }
            classes.add(cls);
        }

        // 4. 构建 objectProperties 和 dataProperties 列表
        List<Map<String, String>> objectProperties = new java.util.ArrayList<>();
        List<Map<String, String>> dataProperties = new java.util.ArrayList<>();

        // 预先构建 classIri → ObjectType 和 namespace → ObjectType 的映射（用于 fallback domain 匹配）
        java.util.Map<String, ObjectType> classByIri = new java.util.LinkedHashMap<>();
        java.util.Map<String, ObjectType> classByNs = new java.util.LinkedHashMap<>();
        for (ObjectType ot : objectTypes) {
            if (ot.getIri() != null) {
                classByIri.put(ot.getIri(), ot);
                String ns = extractNamespace(ot.getIri());
                if (ns != null && !ns.isEmpty()) {
                    classByNs.putIfAbsent(ns, ot);
                }
            }
        }

        for (LinkType lt : linkTypes) {
            Map<String, String> prop = new java.util.LinkedHashMap<>();
            prop.put("id", lt.getId());
            prop.put("iri", lt.getIri());
            prop.put("label", lt.getLabel());
            prop.put("shortName", getShortName(lt.getIri(), lt.getLabel()));

            // domain 处理：优先使用 sourceType，为空时尝试 namespace 推断
            String domain = lt.getSourceType();
            if (domain == null || domain.isBlank()) {
                // 尝试通过属性 IRI 的命名空间匹配类
                if (lt.getIri() != null) {
                    String propNs = extractNamespace(lt.getIri());
                    if (propNs != null && !propNs.isEmpty()) {
                        ObjectType matched = classByNs.get(propNs);
                        if (matched != null) {
                            domain = matched.getIri();
                            log.debug("LinkType '{}' 通过命名空间推断 domain: {}", lt.getIri(), domain);
                        }
                    }
                }
                // namespace 匹配失败，尝试通过 range 在同一命名空间找类
                if ((domain == null || domain.isBlank()) && lt.getTargetType() != null) {
                    String rangeNs = extractNamespace(lt.getTargetType());
                    if (rangeNs != null && !rangeNs.isEmpty()) {
                        ObjectType matched = classByNs.get(rangeNs);
                        if (matched != null) {
                            domain = matched.getIri();
                            log.debug("LinkType '{}' 通过 range 命名空间推断 domain: {}", lt.getIri(), domain);
                        }
                    }
                }
            }
            prop.put("domain", domain);
            prop.put("range", lt.getTargetType());
            if (lt.getDescription() != null && !lt.getDescription().isBlank()) {
                prop.put("description", lt.getDescription());
            }

            // 根据 range 判断是 object property 还是 data property
            String range = lt.getTargetType();
            if (range != null && (range.startsWith("http://www.w3.org/2001/XMLSchema#")
                    || range.startsWith("xsd:"))) {
                prop.put("datatype", range);
                dataProperties.add(prop);
            } else {
                objectProperties.add(prop);
            }
        }

        // 5. 同时从 ObjectType.propertiesJson 中提取数据属性（补充）
        for (ObjectType ot : objectTypes) {
            if (ot.getPropertiesJson() != null && !ot.getPropertiesJson().isBlank()) {
                try {
                    @SuppressWarnings("unchecked")
                    java.util.List<Map<String, Object>> props =
                            objectMapper.readValue(ot.getPropertiesJson(), java.util.List.class);
                    if (props != null) {
                        for (Map<String, Object> p : props) {
                            String propIri = safeString(p.get("iri"));
                            // 避免重复：按 IRI + domain 组合去重（同一IRI可属于不同类）
                            boolean exists = dataProperties.stream()
                                    .anyMatch(dp -> propIri != null && propIri.equals(dp.get("iri"))
                                            && ot.getIri() != null && ot.getIri().equals(dp.get("domain")));
                            if (!exists && propIri != null) {
                                Map<String, String> dp = new java.util.LinkedHashMap<>();
                                dp.put("iri", propIri);
                                dp.put("label", safeString(p.getOrDefault("label", getShortName(propIri, null))));
                                dp.put("shortName", getShortName(propIri, safeString(p.get("label"))));
                                dp.put("domain", ot.getIri());
                                dp.put("datatype", safeString(p.getOrDefault("dataType", "xsd:string")));
                                if (p.containsKey("description") && p.get("description") != null
                                        && !p.get("description").toString().isBlank()) {
                                    dp.put("description", p.get("description").toString());
                                }
                                dataProperties.add(dp);
                            }
                        }
                    }
                } catch (Exception e) {
                    log.debug("解析 ObjectType.propertiesJson 失败: {}", ot.getId(), e);
                }
            }
        }

        // 6. 构建响应
        Map<String, Object> result = new java.util.LinkedHashMap<>();
        result.put("classes", classes);
        result.put("objectProperties", objectProperties);
        result.put("dataProperties", dataProperties);
        result.put("builtins", SwrlBuiltinCatalog.toCategoryList());

        log.info("SWRL 词汇表构建完成: ontologyId={}, classes={}, objProps={}, dataProps={}",
                ontologyId, classes.size(), objectProperties.size(), dataProperties.size());
        return Result.success(result);
    }

    /** 从 IRI 或名称中提取短名称 */
    private String getShortName(String iri, String fallback) {
        if (iri != null && iri.contains("#")) {
            return iri.substring(iri.lastIndexOf('#') + 1);
        }
        if (iri != null && iri.contains("/")) {
            return iri.substring(iri.lastIndexOf('/') + 1);
        }
        return fallback != null ? fallback : (iri != null ? iri : "");
    }

    @PostMapping("/import")
    @Operation(summary = "导入本体")
    public Result<Ontology> importOntology(@RequestBody Map<String, Object> importData) {
        return Result.success(ontologyService.importOntology(importData));
    }

    @PostMapping("/import-ttl")
    @Operation(summary = "直接导入本体模型文件（支持TTL和RDF/XML格式）")
    public Result<Ontology> importTtl(
            @RequestParam("fileId") String fileId,
            @RequestParam("name") String name,
            @RequestParam(value = "versionName", required = false) String versionName) throws Exception {
        if (name == null || name.isBlank()) {
            return Result.badRequest("本体模型名称不能为空");
        }

        // 从存储中读取文件内容
        FileUpload fileUpload = fileUploadService.getFileUpload(fileId);
        StorageStrategy storageStrategy;
        if (fileUpload.getStorageType() != null) {
            storageStrategy = storageStrategyFactory.getStrategy(
                    StorageStrategy.StorageType.valueOf(fileUpload.getStorageType().name()));
        } else {
            storageStrategy = storageStrategyFactory.getDefaultStrategy();
        }
        String originalFilename = fileUpload.getOriginalFilename();
        // ★ 支持 TTL 和 RDF/XML 格式
        boolean isTtl = originalFilename != null && originalFilename.toLowerCase().endsWith(".ttl");
        boolean isRdfXml = originalFilename != null && (originalFilename.toLowerCase().endsWith(".rdf") || originalFilename.toLowerCase().endsWith(".owl"));
        if (!isTtl && !isRdfXml) {
            return Result.badRequest("请上传 .ttl 或 .rdf/.owl 格式的本体文件");
        }

        // 读取文件内容
        String fileContent;
        try (InputStream is = storageStrategy.getFileInputStream(fileUpload.getStoragePath())) {
            fileContent = new String(is.readAllBytes(), StandardCharsets.UTF_8);
        }
        log.info("导入本体文件: {}, 格式: {}, 原始内容长度: {} 字符", originalFilename, isTtl ? "TTL" : "RDF/XML", fileContent.length());

        // ★ RDF/XML 格式：直接转换为 TTL（Jena统一解析）
        String ttlContent;
        if (isRdfXml) {
            log.info("检测到 RDF/XML 格式，转换为 TTL...");
            try {
                // 使用 Jena 将 RDF/XML 转换为 TTL
                org.apache.jena.rdf.model.Model rdfModel = org.apache.jena.rdf.model.ModelFactory.createDefaultModel();
                rdfModel.read(new java.io.ByteArrayInputStream(fileContent.getBytes(StandardCharsets.UTF_8)), null, "RDF/XML");
                
                // 序列化为 TTL
                java.io.ByteArrayOutputStream ttlOutput = new java.io.ByteArrayOutputStream();
                rdfModel.write(ttlOutput, "TURTLE");
                ttlContent = ttlOutput.toString(StandardCharsets.UTF_8.name());
                
                log.info("RDF/XML 转 TTL 成功，转换后长度: {} 字符, 三元组数量: {}", ttlContent.length(), rdfModel.size());
            } catch (Exception e) {
                log.error("RDF/XML 转 TTL 失败: {}", e.getMessage(), e);
                return Result.badRequest("RDF/XML 文件解析失败: " + e.getMessage());
            }
        } else {
            // TTL 格式：直接使用
            ttlContent = fileContent;
            
            // TTL 清理和修复
            ttlContent = cleanTtlContent(ttlContent);
            log.info("cleanTtlContent 后长度: {} 字符", ttlContent.length());
            // 打印前 500 字符用于排查
            String preview = ttlContent.length() > 500 ? ttlContent.substring(0, 500) + "..." : ttlContent;
            log.info("cleanTtlContent 后内容预览:\n{}", preview);

            // ★ 补充 TTL 中缺失但实际使用的前缀声明（如 prop:），防止 Jena 解析报
            //   "Undefined prefix" 错误而整体失败回退到有 bug 的文本解析器
            ttlContent = ensurePrefixDeclared(ttlContent, "prop", ":");
            // ★ 修复 TTL 语法问题（必须在 Jena 解析之前执行）
            String fixedForParsing = TtlSyntaxFixer.fixAll(ttlContent);
            if (!fixedForParsing.equals(ttlContent)) {
                log.info("TTL内容在解析前已修复（缺失前缀、SWRL语法等）, 原始长度={}, 修复后长度={}",
                        ttlContent.length(), fixedForParsing.length());
                ttlContent = fixedForParsing;
            }
        }

        // ===== 解析 TTL 提取类和属性 =====
        int classCount = 0;
        int propertyCount = 0;
        Model jenaModel = null;
        List<ObjectType> parsedObjectTypes = new ArrayList<>();
        List<LinkType> parsedLinkTypes = new ArrayList<>();

        try {
            // 方式一：先用 Jena 解析
            jenaModel = ModelFactory.createDefaultModel();
            jenaModel.read(new java.io.ByteArrayInputStream(ttlContent.getBytes(StandardCharsets.UTF_8)), null, "TURTLE");
            log.info("Jena 解析成功，Model size: {}", jenaModel.size());

            // 提取前缀映射（用于 IRI 展开/缩写）
            java.util.Map<String, String> prefixMap = extractPrefixesFromModel(jenaModel);

            // 收集所有 OWL Class 和 rdfs:Class（显式声明）
            String owlClassUri = "http://www.w3.org/2002/07/owl#Class";
            String rdfsClassUri = "http://www.w3.org/2000/01/rdf-schema#Class";
            Set<org.apache.jena.rdf.model.Resource> classResources = new LinkedHashSet<>();
            jenaModel.listStatements(null, org.apache.jena.vocabulary.RDF.type, (org.apache.jena.rdf.model.RDFNode) null)
                    .filterKeep(s -> s.getObject().isURIResource()
                            && (s.getObject().asResource().getURI().equals(owlClassUri)
                                || s.getObject().asResource().getURI().equals(rdfsClassUri)))
                    .mapWith(org.apache.jena.rdf.model.Statement::getSubject)
                    .filterKeep(org.apache.jena.rdf.model.RDFNode::isURIResource)
                    .forEachRemaining(r -> classResources.add(r));

            // 也收集通过 rdfs:subClassOf 隐式声明的类（LLM 紧凑格式如 :X rdfs:subClassOf :Y .）
            jenaModel.listStatements(null,
                    jenaModel.getProperty("http://www.w3.org/2000/01/rdf-schema#subClassOf"),
                    (org.apache.jena.rdf.model.RDFNode) null)
                    .mapWith(org.apache.jena.rdf.model.Statement::getSubject)
                    .filterKeep(org.apache.jena.rdf.model.RDFNode::isURIResource)
                    .forEachRemaining(r -> classResources.add(r));

            // 收集所有 ObjectProperty / DatatypeProperty / AnnotationProperty
            String objPropUri = "http://www.w3.org/2002/07/owl#ObjectProperty";
            String dtPropUri = "http://www.w3.org/2002/07/owl#DatatypeProperty";
            String annPropUri = "http://www.w3.org/2002/07/owl#AnnotationProperty";
            Set<org.apache.jena.rdf.model.Resource> propResources = new LinkedHashSet<>();
            jenaModel.listStatements(null, org.apache.jena.vocabulary.RDF.type, (org.apache.jena.rdf.model.RDFNode) null)
                    .filterKeep(s -> s.getObject().isURIResource()
                            && (s.getObject().asResource().getURI().equals(objPropUri)
                                || s.getObject().asResource().getURI().equals(dtPropUri)
                                || s.getObject().asResource().getURI().equals(annPropUri)))
                    .mapWith(org.apache.jena.rdf.model.Statement::getSubject)
                    .filterKeep(org.apache.jena.rdf.model.RDFNode::isURIResource)
                    .forEachRemaining(r -> propResources.add(r));

            classCount = classResources.size();
            propertyCount = propResources.size();
            log.info("Jena 提取完成: {} 个类, {} 个属性", classCount, propertyCount);

            // 构建 ObjectType 列表
            for (org.apache.jena.rdf.model.Resource classRes : classResources) {
                String iri = classRes.getURI();
                String localName = extractLocalName(iri);
                String label = getStringValue(jenaModel, classRes,
                        "http://www.w3.org/2000/01/rdf-schema#label");
                if (label == null || label.isBlank()) label = localName;

                String description = getStringValue(jenaModel, classRes,
                        "http://www.w3.org/2000/01/rdf-schema#comment");

                // 收集所有 rdfs:subClassOf，DB模型仅支持单父类，保留第一个
                java.util.List<String> parentIris = new java.util.ArrayList<>();
                org.apache.jena.rdf.model.StmtIterator subIter = jenaModel.listStatements(
                        classRes, jenaModel.getProperty("http://www.w3.org/2000/01/rdf-schema#subClassOf"),
                        (org.apache.jena.rdf.model.RDFNode) null);
                while (subIter.hasNext()) {
                    org.apache.jena.rdf.model.Statement subStmt = subIter.next();
                    if (subStmt.getObject().isURIResource()) {
                        parentIris.add(subStmt.getObject().asResource().getURI());
                    }
                }
                String parentIri = null;
                if (!parentIris.isEmpty()) {
                    parentIri = parentIris.get(0);
                    if (parentIris.size() > 1) {
                        log.warn("类 {} 有 {} 个父类 (rdfs:subClassOf)，DB模型仅支持单父类，保留第一个: {}，丢弃: {}",
                                iri, parentIris.size(), parentIri,
                                parentIris.subList(1, parentIris.size()));
                    }
                }

                parsedObjectTypes.add(ObjectType.builder()
                        .iri(iri)
                        .label(label)
                        .parentIri(parentIri)
                        .description(description)
                        .build());
                log.info("  类: IRI={}, label={}", iri, label);
            }

            // 构建 LinkType 列表（仅 ObjectProperty/AnnotationProperty，DatatypeProperty 走 propertiesJson）
            for (org.apache.jena.rdf.model.Resource propRes : propResources) {
                // 跳过 DatatypeProperty，它们后续会写入 propertiesJson
                if (jenaModel.contains(propRes, org.apache.jena.vocabulary.RDF.type,
                        jenaModel.getResource(dtPropUri))) {
                    continue;
                }
                String iri = propRes.getURI();
                String localName = extractLocalName(iri);
                String label = getStringValue(jenaModel, propRes,
                        "http://www.w3.org/2000/01/rdf-schema#label");
                if (label == null || label.isBlank()) label = localName;

                String description = getStringValue(jenaModel, propRes,
                        "http://www.w3.org/2000/01/rdf-schema#comment");

                String sourceType = null, targetType = null;
                org.apache.jena.rdf.model.Statement domStmt = propRes.getProperty(
                        jenaModel.getProperty("http://www.w3.org/2000/01/rdf-schema#domain"));
                if (domStmt != null && domStmt.getObject().isURIResource()) {
                    sourceType = domStmt.getObject().asResource().getURI();
                }
                org.apache.jena.rdf.model.Statement rangeStmt = propRes.getProperty(
                        jenaModel.getProperty("http://www.w3.org/2000/01/rdf-schema#range"));
                if (rangeStmt != null && rangeStmt.getObject().isURIResource()) {
                    targetType = rangeStmt.getObject().asResource().getURI();
                }

                parsedLinkTypes.add(LinkType.builder()
                        .iri(iri)
                        .label(label)
                        .sourceType(sourceType)
                        .targetType(targetType)
                        .description(description)
                        .build());
                log.info("  属性: IRI={}, label={}", iri, label);
            }

            // 将 DatatypeProperty 按 domain 分组，写入对应 ObjectType 的 propertiesJson
            Map<String, java.util.List<Map<String, String>>> propsByDomain = new LinkedHashMap<>();
            String dtPropUriJena = "http://www.w3.org/2002/07/owl#DatatypeProperty";
            for (org.apache.jena.rdf.model.Resource propRes : propResources) {
                if (!jenaModel.contains(propRes, org.apache.jena.vocabulary.RDF.type,
                        jenaModel.getResource(dtPropUriJena))) {
                    continue;
                }
                String propIri = propRes.getURI();
                String propLabel = getStringValue(jenaModel, propRes,
                        "http://www.w3.org/2000/01/rdf-schema#label");
                if (propLabel == null || propLabel.isBlank()) propLabel = extractLocalName(propIri);

                String propDomain = null;
                org.apache.jena.rdf.model.Statement domStmt2 = propRes.getProperty(
                        jenaModel.getProperty("http://www.w3.org/2000/01/rdf-schema#domain"));
                if (domStmt2 != null && domStmt2.getObject().isURIResource()) {
                    propDomain = domStmt2.getObject().asResource().getURI();
                }

                String propRange = null;
                org.apache.jena.rdf.model.Statement rangeStmt2 = propRes.getProperty(
                        jenaModel.getProperty("http://www.w3.org/2000/01/rdf-schema#range"));
                if (rangeStmt2 != null) {
                    if (rangeStmt2.getObject().isURIResource()) {
                        propRange = rangeStmt2.getObject().asResource().getURI();
                    } else if (rangeStmt2.getObject().isLiteral()) {
                        propRange = rangeStmt2.getObject().asLiteral().getString();
                    }
                }
                if (propRange == null || propRange.isBlank()) propRange = "xsd:string";

                String propComment = getStringValue(jenaModel, propRes,
                        "http://www.w3.org/2000/01/rdf-schema#comment");

                Map<String, String> propEntry = new LinkedHashMap<>();
                propEntry.put("iri", propIri);
                propEntry.put("label", propLabel);
                propEntry.put("dataType", propRange);
                if (propComment != null && !propComment.isBlank()) {
                    propEntry.put("description", propComment);
                }
                String domainKey = (propDomain != null && !propDomain.isBlank()) ? propDomain : "";
                propsByDomain.computeIfAbsent(domainKey, k -> new java.util.ArrayList<>()).add(propEntry);
            }

            if (!propsByDomain.isEmpty()) {
                // 精确匹配：domain 与 ObjectType.iri 一致
                java.util.Set<String> matchedKeys = new java.util.HashSet<>();
                for (ObjectType objType : parsedObjectTypes) {
                    java.util.List<Map<String, String>> attachedProps = propsByDomain.get(objType.getIri());
                    if (attachedProps != null && !attachedProps.isEmpty()) {
                        try {
                            objType.setPropertiesJson(objectMapper.writeValueAsString(attachedProps));
                            matchedKeys.add(objType.getIri());
                        } catch (Exception e) {
                            log.error("序列化 propertiesJson 失败: iri={}", objType.getIri(), e);
                        }
                    }
                }

                // 处理未匹配的属性（domain 为空或 domain 不匹配任何已知类）
                java.util.List<String> unmatchedKeys = new java.util.ArrayList<>();
                for (Map.Entry<String, java.util.List<Map<String, String>>> entry : propsByDomain.entrySet()) {
                    if (!matchedKeys.contains(entry.getKey()) && !entry.getValue().isEmpty()) {
                        unmatchedKeys.add(entry.getKey());
                    }
                }

                if (!unmatchedKeys.isEmpty() && !parsedObjectTypes.isEmpty()) {
                    // 构建 namespace → ObjectType 映射（提取 # 或最后一个 / 之前的 IRI 作为命名空间）
                    java.util.Map<String, ObjectType> nsToClass = new java.util.LinkedHashMap<>();
                    for (ObjectType objType : parsedObjectTypes) {
                        if (objType.getIri() != null) {
                            String ns = extractNamespace(objType.getIri());
                            if (ns != null && !ns.isEmpty()) {
                                nsToClass.putIfAbsent(ns, objType);
                            }
                        }
                    }

                    for (String unmatchedKey : unmatchedKeys) {
                        java.util.List<Map<String, String>> orphanProps = propsByDomain.get(unmatchedKey);
                        ObjectType targetClass = null;

                        // 策略1: 通过 namespace 匹配
                        if (unmatchedKey != null && !unmatchedKey.isEmpty()) {
                            String propNs = extractNamespace(unmatchedKey);
                            if (propNs != null) {
                                targetClass = nsToClass.get(propNs);
                                if (targetClass != null) {
                                    log.info("DatatypeProperty domain '{}' 通过命名空间匹配到类 '{}'",
                                            unmatchedKey, targetClass.getIri());
                                }
                            }
                        }

                        // 策略2: 如果 domain 为空或命名空间匹配失败，逐个比较属性 IRI 命名空间与类命名空间
                        if (targetClass == null && !orphanProps.isEmpty()) {
                            String propIri = orphanProps.get(0).get("iri");
                            if (propIri != null) {
                                String propNs = extractNamespace(propIri);
                                if (propNs != null) {
                                    targetClass = nsToClass.get(propNs);
                                    if (targetClass != null) {
                                        log.info("DatatypeProperty '{}' 通过属性IRI命名空间匹配到类 '{}'",
                                                propIri, targetClass.getIri());
                                    }
                                }
                            }
                        }

                        // 策略3: fallback — 附加到第一个类
                        if (targetClass == null) {
                            targetClass = parsedObjectTypes.get(0);
                            log.warn("DatatypeProperty domain '{}' 无法匹配任何已知类，回退到第一个类 '{}'，共 {} 条属性",
                                    unmatchedKey, targetClass.getIri(), orphanProps.size());
                        }

                        // 合并到目标类的 propertiesJson
                        try {
                            java.util.List<Map<String, String>> existingProps = null;
                            String existingJson = targetClass.getPropertiesJson();
                            if (existingJson != null && !existingJson.isBlank()) {
                                existingProps = objectMapper.readValue(existingJson, java.util.List.class);
                            }
                            java.util.List<Map<String, String>> merged = new java.util.ArrayList<>();
                            if (existingProps != null) {
                                merged.addAll(existingProps);
                            }
                            merged.addAll(orphanProps);
                            targetClass.setPropertiesJson(objectMapper.writeValueAsString(merged));
                            matchedKeys.add(unmatchedKey);
                        } catch (Exception e) {
                            log.error("合并未匹配的 DatatypeProperty 失败: domain={}", unmatchedKey, e);
                        }
                    }
                }
                log.info("已将 DatatypeProperty 关联到对应类: {} 个domain分组, {} 个已匹配",
                        propsByDomain.size(), matchedKeys.size());
            }

        } catch (Exception e) {
            log.error("Jena 解析 TTL 失败: {}", e.getMessage(), e);
            // Jena 解析失败，尝试用文本方式解析
            log.info("尝试文本方式解析 TTL...");
            try {
                String[] lines = ttlContent.split("\n");
                java.util.Map<String, String> textPrefixMap = extractPrefixesManually(Arrays.asList(lines));
                // 已知的 well-known 命名空间 IRI 前缀（用于过滤被误识别为类/属性的谓词行）
                java.util.Set<String> knownNsIris = new java.util.HashSet<>(java.util.Arrays.asList(
                    "http://www.w3.org/1999/02/22-rdf-syntax-ns#",
                    "http://www.w3.org/2000/01/rdf-schema#",
                    "http://www.w3.org/2002/07/owl#",
                    "http://www.w3.org/2001/XMLSchema#",
                    "http://www.w3.org/2003/11/swrl#",
                    "http://www.w3.org/2003/11/swrlb#"
                ));

                for (int i = 0; i < lines.length; i++) {
                    String trimmed = lines[i].trim();
                    // 跳过空行和注释行
                    if (trimmed.isEmpty() || trimmed.startsWith("#")) continue;

                    // 检测是否为类声明行：
                    // 1) 显式 owl:Class/rdfs:Class 声明
                    // 2) LLM 紧凑格式：:X rdfs:subClassOf :Y . （第一个 token 是主语，不可是已知命名空间中的谓词）
                    boolean isExplicitClass = trimmed.contains("a owl:Class") || trimmed.contains("a rdfs:Class");
                    boolean isCompactClass = !isExplicitClass
                            && (trimmed.contains("rdfs:subClassOf") || trimmed.contains("rdf:subClassOf"))
                            && !isStartsWithKnownPredicate(trimmed, textPrefixMap, knownNsIris);

                    if (isExplicitClass || isCompactClass) {
                        String subject = extractSubject(trimmed);
                        if (subject == null || subject.isBlank()) continue;
                        // 跳过 Ontology 本身
                        String fullIri = expandPrefix(subject, textPrefixMap);
                        if (fullIri == null) fullIri = subject;
                        if (fullIri.contains("Ontology") && !fullIri.endsWith("#Ontology") && !fullIri.endsWith("/Ontology")) continue;

                        // 跳过已知命名空间中定义的术语（如 rdfs:subClassOf 被误当作主语的情况）
                        if (isKnownNsTerm(fullIri, knownNsIris)) continue;

                        String label = extractTextProperty(lines, i, "rdfs:label", textPrefixMap);
                        if (label == null || label.isBlank()) label = extractLocalName(fullIri);
                        String description = extractTextProperty(lines, i, "rdfs:comment", textPrefixMap);
                        String parentIri = extractTextProperty(lines, i, "rdfs:subClassOf", textPrefixMap);
                        parsedObjectTypes.add(ObjectType.builder()
                                .iri(fullIri)
                                .label(label)
                                .parentIri(parentIri)
                                .description(description)
                                .build());
                        classCount++;
                        log.info("  [文本解析] 类: IRI={}", fullIri);
                    } else if (trimmed.contains("a owl:ObjectProperty") || trimmed.contains("a owl:DatatypeProperty")
                            || trimmed.contains("a owl:AnnotationProperty")) {
                        String subject = extractSubject(trimmed);
                        if (subject == null || subject.isBlank()) continue;

                        String fullIri = expandPrefix(subject, textPrefixMap);
                        if (fullIri == null) fullIri = subject;

                        // 跳过已知命名空间的术语
                        if (isKnownNsTerm(fullIri, knownNsIris)) continue;

                        String label = extractTextProperty(lines, i, "rdfs:label", textPrefixMap);
                        if (label == null || label.isBlank()) label = extractLocalName(fullIri);
                        String propComment = extractTextProperty(lines, i, "rdfs:comment", textPrefixMap);
                        String sourceType = extractTextProperty(lines, i, "rdfs:domain", textPrefixMap);
                        String targetType = extractTextProperty(lines, i, "rdfs:range", textPrefixMap);
                        parsedLinkTypes.add(LinkType.builder()
                                .iri(fullIri)
                                .label(label)
                                .sourceType(sourceType)
                                .targetType(targetType)
                                .description(propComment)
                                .build());
                        propertyCount++;
                        log.info("  [文本解析] 属性: IRI={}", fullIri);
                    }
                }
            } catch (Exception e2) {
                log.error("文本方式解析也失败: {}", e2.getMessage(), e2);
            }
        }

        log.info("最终解析结果: {} 个类, {} 个属性", classCount, propertyCount);

        // ---- 查找同名本体的最新版本号 ----
        String nextVersion = "1.0.0";
        List<Ontology> existing = ontologyService.findAll().stream()
                .filter(o -> name.equals(o.getName()))
                .sorted((a, b) -> compareVersions(b.getVersion(), a.getVersion()))
                .toList();
        if (!existing.isEmpty()) {
            nextVersion = incrementVersion(existing.get(0).getVersion());
        }

        // ---- 修复TTL语法（中文IRI、SWRL语法等），确保Protégé/OWL API可正确识别 ----
        String fixedTtlContent = TtlSyntaxFixer.fixAll(ttlContent);
        if (!fixedTtlContent.equals(ttlContent)) {
            log.info("TTL内容已修复（中文IRI、SWRL语法等）, 原始长度={}, 修复后长度={}", ttlContent.length(), fixedTtlContent.length());
            ttlContent = fixedTtlContent;
        }

        // ---- 保存 TTL 文件 ----
        String ttlDir = storageBaseDir + "/ttl";
        Files.createDirectories(Paths.get(ttlDir));
        String ontologyId = java.util.UUID.randomUUID().toString();

        // 保留原始 TTL 命名空间，不做替换

        String ttlPath = ttlDir + "/" + ontologyId + ".ttl";
        Files.writeString(Paths.get(ttlPath), ttlContent, StandardCharsets.UTF_8);

        // ---- 创建本体记录 ----
        Ontology ontology = Ontology.builder()
                .name(name)
                .versionName(versionName)
                .version(nextVersion)
                .classCount(classCount)
                .propertyCount(propertyCount)
                .ttlPath(ttlPath)
                .sourceMdFileName(originalFilename)
                .status(Ontology.Status.draft)
                .build();
        Ontology saved = ontologyService.create(ontology);

        // 自动加载 TTL 到 TDB2 三元组存储，使其立即可查询
        try {
            reasoningService.loadTtlContent(ttlContent, saved.getId());
            saved.setInstantiated(true);
            ontologyService.update(saved.getId(), saved);
            log.info("本体保存并自动加载到TDB2: id={}, name={}", saved.getId(), name);
        } catch (Exception e) {
            log.warn("本体已保存但自动加载TDB2失败（不影响本体保存）: id={}, error={}", saved.getId(), e.getMessage());
        }

        // ---- 将解析出的类和属性写入数据库 ----
        for (ObjectType objectType : parsedObjectTypes) {
            objectType.setOntologyId(saved.getId());
            ontologyService.createObjectType(saved.getId(), objectType);
        }
        for (LinkType linkType : parsedLinkTypes) {
            linkType.setOntologyId(saved.getId());
            ontologyService.createLinkType(saved.getId(), linkType);
        }

        // 提取并持久化 SWRL 规则为 BusinessRule 实体
        int swrlRuleCount = 0;
        if (jenaModel != null && !jenaModel.isEmpty()) {
            try {
                swrlRuleCount = extractAndPersistSwrlRules(jenaModel, saved.getId());
            } catch (Exception e) {
                log.warn("从TTL提取SWRL规则失败（不影响本体导入）: ontologyId={}, error={}",
                        saved.getId(), e.getMessage(), e);
            }
        }

        log.info("TTL 导入完成: 本体={}, 类={}, 属性={}, SWRL规则={}",
                saved.getId(), classCount, propertyCount, swrlRuleCount);
        return Result.success(saved);
    }

    @PostMapping("/{id}/validate")
    @Operation(summary = "验证本体")
    public Result<Map<String, Object>> validate(@PathVariable String id) {
        return Result.success(ontologyService.validate(id));
    }

    @PostMapping("/{id}/auto-fix")
    @Operation(summary = "自动修复本体错误和警告", description = "根据校验结果中用户选择的错误项和警告项自动修复数据库记录和TTL文件")
    public Result<Map<String, Object>> autoFix(@PathVariable String id, @RequestBody Map<String, Object> body) {
        try {
            // 解析错误索引
            @SuppressWarnings("unchecked")
            List<Number> selectedErrorIndices = (List<Number>) body.get("selectedErrorIndices");
            List<Integer> errorIndices = new ArrayList<>();
            if (selectedErrorIndices != null) {
                for (Number n : selectedErrorIndices) {
                    errorIndices.add(n.intValue());
                }
            }

            // 解析警告索引（可选）
            @SuppressWarnings("unchecked")
            List<Number> selectedWarningIndices = (List<Number>) body.get("selectedWarningIndices");
            List<Integer> warningIndices = new ArrayList<>();
            if (selectedWarningIndices != null) {
                for (Number n : selectedWarningIndices) {
                    warningIndices.add(n.intValue());
                }
            }

            // 如果提供了警告索引，使用新的重载方法
            Map<String, Object> result;
            if (!warningIndices.isEmpty()) {
                result = ontologyService.autoFix(id, errorIndices, warningIndices);
            } else {
                result = ontologyService.autoFix(id, errorIndices);
            }
            return Result.success(result);
        } catch (Exception e) {
            log.error("自动修复失败: id={}", id, e);
            return Result.error("自动修复失败: " + e.getMessage());
        }
    }

    @GetMapping("/{id}/export")
    @Operation(summary = "导出本体(TTL)")
    public ResponseEntity<Resource> exportTtl(@PathVariable String id) throws IOException {
        Ontology ontology = ontologyService.findById(id);
        if (ontology.getTtlPath() == null) {
            return ResponseEntity.notFound().build();
        }
        Path filePath = Paths.get(ontology.getTtlPath());
        if (!Files.exists(filePath)) {
            return ResponseEntity.notFound().build();
        }
        Resource resource = new FileSystemResource(filePath);
        String encodedName = URLEncoder.encode(ontology.getName() + "_v" + ontology.getVersion() + ".ttl", StandardCharsets.UTF_8);
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType("text/turtle"))
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename*=UTF-8''" + encodedName)
                .body(resource);
    }

    @GetMapping("/{id}/export-markdown")
    @Operation(summary = "导出本体 Markdown 文档")
    public ResponseEntity<byte[]> exportMarkdown(@PathVariable String id) throws IOException {
        Ontology ontology = ontologyService.findById(id);
        List<ObjectType> objectTypes = ontologyService.findObjectTypes(id);
        List<LinkType> linkTypes = ontologyService.findLinkTypes(id);
        List<BusinessRule> rules = ruleService.findByOntologyId(id);

        StringBuilder md = new StringBuilder();
        md.append("# ").append(ontology.getName() != null ? ontology.getName() : "未命名本体").append("\n\n");

        // 版本信息
        md.append("- **版本号**: v").append(ontology.getVersion() != null ? ontology.getVersion() : "1.0.0").append("\n");
        if (ontology.getVersionName() != null && !ontology.getVersionName().isBlank()) {
            md.append("- **版本名称**: ").append(ontology.getVersionName()).append("\n");
        }
        md.append("- **创建时间**: ").append(ontology.getCreatedAt() != null ? ontology.getCreatedAt().toString() : "").append("\n");
        md.append("- **更新时间**: ").append(ontology.getUpdatedAt() != null ? ontology.getUpdatedAt().toString() : "").append("\n\n");

        // 本体描述
        md.append("## 本体描述\n\n");
        if (ontology.getDescription() != null && !ontology.getDescription().isBlank()) {
            md.append(ontology.getDescription()).append("\n\n");
        } else {
            md.append("暂无描述\n\n");
        }

        // 类层次
        md.append("## 类层次\n\n");
        if (objectTypes != null && !objectTypes.isEmpty()) {
            md.append("| IRI | 标签 | 父类 | 描述 |\n");
            md.append("|-----|------|------|------|\n");
            for (ObjectType cls : objectTypes) {
                String iri = cls.getIri() != null ? cls.getIri() : "";
                String label = cls.getLabel() != null ? cls.getLabel() : "";
                String parent = cls.getParentIri() != null ? cls.getParentIri() : "-";
                String desc = cls.getDescription() != null ? cls.getDescription() : "";
                md.append("| `").append(iri).append("` | ").append(label)
                  .append(" | ").append(parent).append(" | ").append(desc).append(" |\n");
            }
        } else {
            md.append("暂无类定义\n");
        }
        md.append("\n");

        // 对象属性
        md.append("## 对象属性\n\n");
        if (linkTypes != null && !linkTypes.isEmpty()) {
            md.append("| IRI | 标签 | Domain | Range | 描述 |\n");
            md.append("|-----|------|--------|-------|------|\n");
            for (LinkType lt : linkTypes) {
                String iri = lt.getIri() != null ? lt.getIri() : "";
                String label = lt.getLabel() != null ? lt.getLabel() : "";
                String domain = lt.getSourceType() != null ? lt.getSourceType() : "";
                String range = lt.getTargetType() != null ? lt.getTargetType() : "";
                String desc = lt.getDescription() != null ? lt.getDescription() : "";
                md.append("| `").append(iri).append("` | ").append(label)
                  .append(" | ").append(domain).append(" | ").append(range)
                  .append(" | ").append(desc).append(" |\n");
            }
        } else {
            md.append("暂无对象属性\n");
        }
        md.append("\n");

        // SWRL 规则
        md.append("## SWRL 规则\n\n");
        if (rules != null && !rules.isEmpty()) {
            for (BusinessRule rule : rules) {
                md.append("### ").append(rule.getName() != null ? rule.getName() : "未命名规则").append("\n\n");
                if (rule.getDescription() != null && !rule.getDescription().isBlank()) {
                    md.append("- **描述**: ").append(rule.getDescription()).append("\n");
                }
                if (rule.getType() != null) {
                    md.append("- **类型**: ").append(rule.getType().name()).append("\n");
                }
                if (rule.getPriority() != null) {
                    md.append("- **优先级**: ").append(rule.getPriority().name()).append("\n");
                }
                if (rule.getConditionJson() != null && !rule.getConditionJson().isBlank()) {
                    md.append("- **条件**: ").append(rule.getConditionJson()).append("\n");
                }
                if (rule.getActionJson() != null && !rule.getActionJson().isBlank()) {
                    md.append("- **动作**: ").append(rule.getActionJson()).append("\n");
                }
                if (rule.getSwrlRule() != null && !rule.getSwrlRule().isBlank()) {
                    md.append("- **SWRL**: `").append(rule.getSwrlRule()).append("`\n");
                }
                md.append("\n");
            }
        } else {
            md.append("暂无业务规则\n\n");
        }

        byte[] bytes = md.toString().getBytes(StandardCharsets.UTF_8);
        String encodedName = URLEncoder.encode(ontology.getName() + "_v" + ontology.getVersion() + ".md", StandardCharsets.UTF_8);
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType("text/markdown; charset=UTF-8"))
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename*=UTF-8''" + encodedName)
                .body(bytes);
    }

    @GetMapping("/{id}/individuals")
    @Operation(summary = "获取本体个体列表")
    public Result<List<Map<String, Object>>> getIndividuals(@PathVariable String id) {
        Ontology ontology = ontologyService.findById(id);
        List<Map<String, Object>> individuals = new ArrayList<>();
        if (ontology.getTtlPath() == null) {
            return Result.success(individuals);
        }
        try {
            Path ttlPath = Paths.get(ontology.getTtlPath());
            if (!Files.exists(ttlPath)) {
                return Result.success(individuals);
            }
            String content = Files.readString(ttlPath, StandardCharsets.UTF_8);

            // 解析个体：匹配 IRI a owl:NamedIndividual 的模式
            // 格式如: :IndividualName a owl:NamedIndividual , :ClassName ;
            java.util.regex.Pattern indivPattern = java.util.regex.Pattern.compile(
                "(\\S+)\\s+a\\s+owl:NamedIndividual\\s*,\\s*(\\S+)\\s*;");
            java.util.regex.Matcher m = indivPattern.matcher(content);

            while (m.find()) {
                String iri = m.group(1);
                String classIri = m.group(2).replaceAll("[,;]\\s*$", "");
                Map<String, Object> indiv = new HashMap<>();
                indiv.put("iri", iri);
                indiv.put("classIri", classIri);

                // 提取 rdfs:label
                java.util.regex.Pattern labelPattern = java.util.regex.Pattern.compile(
                    java.util.regex.Pattern.quote(iri) + "\\s+[^.]*?rdfs:label\\s+\"([^\"]*)\"");
                java.util.regex.Matcher labelMatcher = labelPattern.matcher(content);
                if (labelMatcher.find()) {
                    indiv.put("label", labelMatcher.group(1));
                } else {
                    indiv.put("label", iri);
                }

                // 提取数据属性值 (在同一个个体声明块内)
                List<Map<String, String>> dataProps = new ArrayList<>();
                int startPos = m.start();
                int endPos = content.indexOf(".", m.end());
                if (endPos < 0) endPos = content.length();
                String indivBlock = content.substring(startPos, endPos);

                java.util.regex.Pattern dpPattern = java.util.regex.Pattern.compile(
                    ":(\\w+)\\s+\"([^\"]*)\"");
                java.util.regex.Matcher dpMatcher = dpPattern.matcher(indivBlock);
                while (dpMatcher.find()) {
                    String propName = dpMatcher.group(1);
                    String value = dpMatcher.group(2);
                    if (!"rdfs:label".contains(propName) && !propName.equals("type")) {
                        Map<String, String> dp = new HashMap<>();
                        dp.put("property", ":" + propName);
                        dp.put("value", value);
                        dataProps.add(dp);
                    }
                }

                indiv.put("dataPropertyValues", dataProps);
                individuals.add(indiv);
            }

            // 如果没有通过 NamedIndividual 格式找到个体，尝试匹配 rdf:type 格式
            if (individuals.isEmpty()) {
                java.util.regex.Pattern fallbackPattern = java.util.regex.Pattern.compile(
                    "(\\S+)\\s+rdf:type\\s+owl:NamedIndividual\\s*,\\s*(\\S+)\\s*;");
                java.util.regex.Matcher fm = fallbackPattern.matcher(content);
                while (fm.find()) {
                    Map<String, Object> indiv = new HashMap<>();
                    indiv.put("iri", fm.group(1));
                    indiv.put("classIri", fm.group(2));
                    indiv.put("label", fm.group(1));
                    indiv.put("dataPropertyValues", new ArrayList<>());
                    individuals.add(indiv);
                }
            }
        } catch (Exception e) {
            log.warn("解析个体失败: id={}, error={}", id, e.getMessage());
        }
        return Result.success(individuals);
    }

    // ===== 新增：Markdown 导入提取 =====

    @PostMapping("/import-markdown")
    @Operation(summary = "导入 Markdown 并提取本体", description = "根据文件ID读取Markdown内容，调用 Agent/LLM 自动提取本体模型")
    public Result<Map<String, Object>> importFromMarkdown(
            @RequestParam("fileId") String fileId,
            @RequestParam(value = "providerId", required = false) String providerId,
            @RequestParam(value = "modelId", required = false) String modelId,
            @RequestParam(value = "systemPrompt", required = false) String systemPrompt) throws Exception {
        // 从存储中读取文件内容
        FileUpload fileUpload = fileUploadService.getFileUpload(fileId);
        StorageStrategy storageStrategy;
        if (fileUpload.getStorageType() != null) {
            storageStrategy = storageStrategyFactory.getStrategy(
                    StorageStrategy.StorageType.valueOf(fileUpload.getStorageType().name()));
        } else {
            storageStrategy = storageStrategyFactory.getDefaultStrategy();
        }
        String filename = fileUpload.getOriginalFilename() != null ? fileUpload.getOriginalFilename() : "";
        String markdownContent;
        try (InputStream is = storageStrategy.getFileInputStream(fileUpload.getStoragePath())) {
            markdownContent = new String(is.readAllBytes(), StandardCharsets.UTF_8);
        }

        log.info("接收到 Markdown 文件: {}, 大小: {} 字符, 模型: {}/{}", filename, markdownContent.length(), providerId, modelId);

        // 尝试调用 Agent 服务进行本体提取
        Map<String, Object> extractionResult;
        try {
            extractionResult = callAgentForExtraction(markdownContent, filename, providerId, modelId, systemPrompt);
        } catch (Exception e) {
            log.warn("Agent 调用失败，使用本地规则提取: {}", e.getMessage());
            extractionResult = localRuleExtraction(markdownContent);
        }

        return Result.success(extractionResult);
    }

    @PostMapping(value = "/import-markdown-stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    @Operation(summary = "流式导入 Markdown 并提取本体", description = "根据文件ID读取Markdown内容，SSE 实时返回 LLM 生成内容")
    public SseEmitter importFromMarkdownStream(
            @RequestParam("fileId") String fileId,
            @RequestParam(value = "providerId", required = false) String providerId,
            @RequestParam(value = "modelId", required = false) String modelId,
            @RequestParam(value = "systemPrompt", required = false) String systemPrompt,
            jakarta.servlet.http.HttpServletRequest httpRequest) {
        // 优先从 POST body 的 JSON 中读取 systemPrompt（避免 URL 过长导致 431 错误）
        if ((systemPrompt == null || systemPrompt.isBlank())) {
            try {
                String contentType = httpRequest.getContentType();
                if (contentType != null && contentType.contains("application/json")) {
                    String body = new String(httpRequest.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
                    if (!body.isBlank()) {
                        com.fasterxml.jackson.databind.JsonNode node = new com.fasterxml.jackson.databind.ObjectMapper().readTree(body);
                        if (node.has("systemPrompt")) {
                            systemPrompt = node.get("systemPrompt").asText();
                        }
                    }
                }
            } catch (Exception e) {
                // 忽略 JSON 解析错误，使用 null systemPrompt
            }
        }
        // 使用 final 变量供 lambda 引用
        final String finalSystemPrompt = systemPrompt;
        SseEmitter emitter = new SseEmitter(21_600_000L); // 2 小时超时
        sseExecutor.execute(() -> {
            java.util.concurrent.ScheduledExecutorService heartbeatScheduler = java.util.concurrent.Executors.newSingleThreadScheduledExecutor();
            java.util.concurrent.ScheduledFuture<?> heartbeatFuture = null;
            try {
                heartbeatFuture = heartbeatScheduler.scheduleAtFixedRate(() -> {
                    try {
                        emitter.send(SseEmitter.event().comment("heartbeat"));
                    } catch (Exception ignored) {}
                }, 15, 15, java.util.concurrent.TimeUnit.SECONDS);

                // 从存储中读取文件内容（与 FileUploadController 相同的方式）
                FileUpload fileUpload = fileUploadService.getFileUpload(fileId);
                StorageStrategy storageStrategy;
                if (fileUpload.getStorageType() != null) {
                    storageStrategy = storageStrategyFactory.getStrategy(
                            StorageStrategy.StorageType.valueOf(fileUpload.getStorageType().name()));
                } else {
                    storageStrategy = storageStrategyFactory.getDefaultStrategy();
                }
                String filename = fileUpload.getOriginalFilename() != null ? fileUpload.getOriginalFilename() : "";
                String markdownContent;
                try (InputStream is = storageStrategy.getFileInputStream(fileUpload.getStoragePath())) {
                    markdownContent = new String(is.readAllBytes(), StandardCharsets.UTF_8);
                }

                log.info("Markdown提取本体: fileId={}, filename={}, contentLen={}", fileId, filename, markdownContent.length());

                Map<String, Object> requestBody = new HashMap<>();
                requestBody.put("markdown", markdownContent != null ? markdownContent : "");
                requestBody.put("filename", filename != null ? filename : "");
                if (providerId != null && !providerId.isBlank()) {
                    requestBody.put("provider_id", providerId);
                }
                if (modelId != null && !modelId.isBlank()) {
                    requestBody.put("model_id", modelId);
                }
                if (finalSystemPrompt != null && !finalSystemPrompt.isBlank()) {
                    requestBody.put("system_prompt", finalSystemPrompt);
                }
                String jsonBody = objectMapper.writeValueAsString(requestBody);

                log.info("Markdown提取本体请求: contentLen={}, jsonBodyLen={}, providerId={}, modelId={}",
                        markdownContent.length(), jsonBody.length(), providerId, modelId);

                java.net.http.HttpClient client = SHARED_HTTP_CLIENT;

                java.net.http.HttpRequest request = java.net.http.HttpRequest.newBuilder()
                        .uri(java.net.URI.create(agentServiceUrl + "/agent/extract-ontology-stream"))
                        .header("Content-Type", "application/json")
                        .POST(java.net.http.HttpRequest.BodyPublishers.ofString(jsonBody))
                        .timeout(java.time.Duration.ofSeconds(21600))
                        .build();

                log.info("发送请求到 Agent 服务: {}", agentServiceUrl + "/agent/extract-ontology-stream");
                java.net.http.HttpResponse<InputStream> response = client.send(request,
                        java.net.http.HttpResponse.BodyHandlers.ofInputStream());

                log.info("Agent 服务响应: statusCode={}", response.statusCode());

                int statusCode = response.statusCode();
                if (statusCode != 200) {
                    String errorBody = new String(response.body().readAllBytes(), StandardCharsets.UTF_8);
                    log.error("Agent服务返回非200状态码: status={}, body={}", statusCode, errorBody);
                    Map<String, Object> errorData = new HashMap<>();
                    errorData.put("error", true);
                    // 解析 FastAPI 错误详情，提供更有用的错误信息
                    String errorMessage = "AI服务异常(HTTP " + statusCode + ")";
                    if (errorBody.contains("detail")) {
                        try {
                            com.fasterxml.jackson.databind.JsonNode errorJson = objectMapper.readTree(errorBody);
                            com.fasterxml.jackson.databind.JsonNode detailNode = errorJson.get("detail");
                            if (detailNode != null) {
                                if (detailNode.isArray() && detailNode.size() > 0) {
                                    // FastAPI 422 验证错误格式: [{"msg": "...", "type": "..."}]
                                    StringBuilder details = new StringBuilder();
                                    for (com.fasterxml.jackson.databind.JsonNode d : detailNode) {
                                        String msg = d.has("msg") ? d.get("msg").asText() : "";
                                        String type = d.has("type") ? d.get("type").asText() : "";
                                        if (!msg.isEmpty()) {
                                            if (details.length() > 0) details.append("; ");
                                            details.append(msg);
                                            if (!type.isEmpty()) details.append(" (").append(type).append(")");
                                        }
                                    }
                                    if (details.length() > 0) {
                                        errorMessage = "请求参数验证失败: " + details;
                                    }
                                } else if (detailNode.isTextual()) {
                                    // FastAPI 500 错误格式: {"detail": "本体提取失败: ..."}
                                    String detailText = detailNode.asText();
                                    if (!detailText.isEmpty()) {
                                        errorMessage = detailText;
                                    }
                                }
                            }
                        } catch (Exception parseEx) {
                            log.warn("解析错误详情失败: {}", parseEx.getMessage());
                        }
                    } else if (statusCode == 422) {
                        errorMessage = "AI服务请求格式错误(422): " + (errorBody.length() > 200 ? errorBody.substring(0, 200) + "..." : errorBody);
                    } else if (statusCode == 500 && !errorBody.isEmpty()) {
                        // 尝试从500响应体中提取有用的错误信息
                        String truncated = errorBody.length() > 300 ? errorBody.substring(0, 300) + "..." : errorBody;
                        errorMessage = "AI服务内部错误: " + truncated;
                    }
                    errorData.put("message", errorMessage);
                    emitter.send(SseEmitter.event().data(errorData, MediaType.APPLICATION_JSON));
                    emitter.completeWithError(new RuntimeException("Agent returned HTTP " + statusCode + ": " + errorBody));
                    return;
                }

                try (BufferedReader reader = new BufferedReader(
                        new InputStreamReader(response.body(), StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        if (line.startsWith("data:")) {
                            String data = line.substring(5).trim();
                            if (!data.isEmpty()) {
                                log.debug("SSE转发: {}", data.length() > 200 ? data.substring(0, 200) + "..." : data);
                                emitter.send(SseEmitter.event().data(data));
                                if (data.contains("\"done\":true") || data.contains("\"done\": true")) {
                                    log.info("SSE流式提取完成，收到done信号");
                                    break;
                                }
                            }
                        }
                    }
                }
                log.info("SSE emitter complete");
                emitter.complete();
            } catch (java.io.IOException e) {
                String msg = e.getMessage() != null ? e.getMessage() : "";
                log.error("SSE 流式提取 IOException: {}", msg, e);
                try {
                    String errorMsg;
                    if (e instanceof java.net.ConnectException) {
                        errorMsg = "无法连接Agent服务，请确认Agent已启动";
                    } else if ("closed".equalsIgnoreCase(msg) || msg.toLowerCase().contains("connection")) {
                        errorMsg = "AI模型服务连接中断，请检查模型配置（API地址、密钥）或稍后重试";
                    } else {
                        errorMsg = "AI服务通信异常: " + msg;
                    }
                    Map<String, Object> errorData = new HashMap<>();
                    errorData.put("error", true);
                    errorData.put("message", errorMsg);
                    emitter.send(SseEmitter.event().data(errorData, MediaType.APPLICATION_JSON));
                } catch (Exception ignored) {}
                emitter.completeWithError(e);
            } catch (Exception e) {
                log.error("SSE 流式提取失败", e);
                try {
                    String errorMsg;
                    if (e instanceof java.net.ConnectException) {
                        errorMsg = "无法连接Agent服务，请确认Agent已启动";
                    } else {
                        errorMsg = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
                    }
                    Map<String, Object> errorData = new HashMap<>();
                    errorData.put("error", true);
                    errorData.put("message", errorMsg);
                    emitter.send(SseEmitter.event().data(errorData, MediaType.APPLICATION_JSON));
                } catch (Exception ignored) {}
                emitter.completeWithError(e);
            } finally {
                if (heartbeatFuture != null) heartbeatFuture.cancel(false);
                heartbeatScheduler.shutdown();
            }
        });
        return emitter;
    }

    @PostMapping("/{id}/save-extraction")
    @Operation(summary = "保存提取结果为本体")
    public Result<Ontology> saveExtraction(@PathVariable String id, @RequestBody Map<String, Object> extractionData) {
        try {
            return doSaveExtraction(id, extractionData);
        } catch (Exception e) {
            log.error("保存提取结果失败", e);
            String msg = e.getMessage();
            if (msg != null && msg.contains("tm_business_rule_type_check")) {
                return Result.error(500, "保存失败：业务规则类型不支持（数据库约束未包含SWRL类型），请联系管理员执行SQL修复");
            }
            return Result.error(500, "保存提取结果失败: " + (msg != null ? msg : e.getClass().getSimpleName()));
        }
    }

    /**
     * 安全地将 Object 转换为 String。
     * 处理 Jackson 反序列化时 LinkedHashMap 等非 String 类型的兼容转换。
     */
    private String safeString(Object obj) {
        if (obj == null) return null;
        if (obj instanceof String s) return s;
        if (obj instanceof Map) {
            try {
                return objectMapper.writeValueAsString(obj);
            } catch (Exception e) {
                return obj.toString();
            }
        }
        return obj.toString();
    }

    /**
     * 从 Map 中获取值，优先取 primaryKey，为空时回退到 fallbackKey。
     * 使用原始 Map 访问避免泛型 CHECKCAST 导致 LinkedHashMap→String 转换失败。
     */
    private String getWithFallback(Map<String, String> map, String primaryKey, String fallbackKey) {
        java.util.Map<?, ?> rawMap = (java.util.Map<?, ?>) map;
        String value = safeString(rawMap.get(primaryKey));
        if (value == null || value.isEmpty()) {
            value = safeString(rawMap.get(fallbackKey));
        }
        return value != null ? value : "";
    }

    /**
     * 获取父类 IRI，优先取 parentIri，为空时尝试从 subClassOf 数组取第一个元素。
     * 使用原始 Map 访问以避免 subClassOf 为 List 类型时的 ClassCastException。
     */
    private String getParentIri(Map<String, String> cls) {
        Object parentIriRaw = ((java.util.Map<?, ?>) cls).get("parentIri");
        String parentIri = safeString(parentIriRaw);
        if (parentIri != null && !parentIri.isEmpty()) {
            return parentIri;
        }
        // subClassOf 在 JSON 中可能是数组 ["ClassName"]，反序列化到 Map<String,String> 时类型不确定
        // 使用原始 Map 访问避免泛型 CHECKCAST
        try {
            Object value = ((java.util.Map<?, ?>) cls).get("subClassOf");
            if (value instanceof String) {
                String s = (String) value;
                if (!s.isEmpty() && !s.equals("[]")) {
                    // 可能已经是纯字符串，也可能包裹了 JSON 数组格式
                    try {
                        java.util.List<String> list = objectMapper.readValue(s, new com.fasterxml.jackson.core.type.TypeReference<java.util.List<String>>() {});
                        if (!list.isEmpty()) return list.get(0);
                    } catch (Exception e) {
                        return s.replaceAll("^\\[?\"|\"\\]?$", "").trim();
                    }
                }
            } else if (value instanceof java.util.List) {
                java.util.List<?> list = (java.util.List<?>) value;
                if (!list.isEmpty()) {
                    return String.valueOf(list.get(0));
                }
            }
        } catch (Exception e) {
            // 类型不匹配时忽略
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private Result<Ontology> doSaveExtraction(String id, Map<String, Object> extractionData) throws Exception {
        Ontology ontology = ontologyService.findById(id);

        // 生成 TTL 文件
        String ttlContent = safeString(extractionData.get("ttlContent"));
        if (ttlContent == null || ttlContent.isBlank()) {
            ttlContent = generateTtlFromExtraction(extractionData);
        }
        ttlContent = cleanTtlContent(ttlContent);

        // 修复TTL语法（中文IRI、SWRL语法等），确保Protégé/OWL API可正确识别
        String fixedTtlContent = TtlSyntaxFixer.fixAll(ttlContent);
        if (!fixedTtlContent.equals(ttlContent)) {
            log.info("TTL内容已修复（中文IRI、SWRL语法等）");
            ttlContent = fixedTtlContent;
        }

        // ★ TTL 完整性校验（与 doSaveVersion 相同逻辑，按唯一IRI集合对比）
        @SuppressWarnings("unchecked")
        List<Map<String, String>> objPropsCheck = (List<Map<String, String>>) extractionData.get("objectProperties");
        @SuppressWarnings("unchecked")
        List<Map<String, String>> dataPropsCheck = (List<Map<String, String>>) extractionData.get("dataProperties");
        java.util.Set<String> ttlObjPropIris = extractUniqueIris(ttlContent, "owl:ObjectProperty");
        java.util.Set<String> ttlDataPropIris = extractUniqueIris(ttlContent, "owl:DatatypeProperty");
        java.util.Set<String> jsonObjPropIris = extractUniqueIrisFromJsonProps(objPropsCheck);
        java.util.Set<String> jsonDataPropIris = extractUniqueIrisFromJsonProps(dataPropsCheck);
        boolean objPropsIncomplete = !jsonObjPropIris.isEmpty() && ttlObjPropIris.size() < jsonObjPropIris.size() * 0.8;
        boolean dataPropsIncomplete = !jsonDataPropIris.isEmpty() && ttlDataPropIris.size() < jsonDataPropIris.size() * 0.8;
        if (objPropsIncomplete || dataPropsIncomplete) {
            log.warn("TTL属性声明不完整(doSaveExtraction): JSON有{}个唯一对象属性IRI/{}个唯一数据属性IRI, TTL仅匹配到{}个/{}个. 改用generateTtlFromExtraction重建TTL.",
                    jsonObjPropIris.size(), jsonDataPropIris.size(), ttlObjPropIris.size(), ttlDataPropIris.size());
            ttlContent = generateTtlFromExtraction(extractionData);
        }

        // 保存 TTL 文件
        String ttlDir = storageBaseDir + "/ttl";
        String ttlPath = ttlDir + "/" + id + ".ttl";
        try {
            Files.createDirectories(Paths.get(ttlDir));
            Files.writeString(Paths.get(ttlPath), ttlContent, StandardCharsets.UTF_8);
        } catch (IOException e) {
            log.error("TTL 文件写入失败: path={}, error={}", ttlPath, e.getMessage(), e);
            throw new RuntimeException("TTL 文件保存失败: " + e.getMessage(), e);
        }

        // 更新本体元数据
        @SuppressWarnings("unchecked")
        List<Map<String, String>> classes = (List<Map<String, String>>) extractionData.get("classes");
        @SuppressWarnings("unchecked")
        List<Map<String, String>> objProps = (List<Map<String, String>>) extractionData.get("objectProperties");
        @SuppressWarnings("unchecked")
        List<Map<String, String>> dataProps = (List<Map<String, String>>) extractionData.get("dataProperties");

        ontology.setTtlPath(ttlPath);
        ontology.setClassCount(classes != null ? classes.size() : 0);
        ontology.setPropertyCount(((objProps != null ? objProps.size() : 0) + (dataProps != null ? dataProps.size() : 0)));

        Ontology saved = ontologyService.update(id, ontology);

        // 自动加载 TTL 到 TDB2 三元组存储，使其立即可查询
        try {
            reasoningService.loadTtlContent(ttlContent, id);
            saved.setInstantiated(true);
            ontologyService.update(id, saved);
            log.info("本体提取结果已自动加载到TDB2: id={}", id);
        } catch (Exception e) {
            log.warn("本体已保存但自动加载TDB2失败（不影响本体保存）: id={}, error={}", id, e.getMessage());
        }

        // 同时创建对象类型和链接类型记录
        if (classes != null) {
            for (Map<String, String> cls : classes) {
                ObjectType objType = new ObjectType();
                objType.setOntologyId(id);
                objType.setIri(cleanIri(getWithFallback(cls, "iri", "id")));
                objType.setLabel(getWithFallback(cls, "label", "name"));
                objType.setParentIri(cleanIri(getParentIri(cls)));
                objType.setDescription(safeString(((java.util.Map<?, ?>) cls).get("description")));
                ontologyService.createObjectType(id, objType);
            }
        }
        if (objProps != null) {
            for (Map<String, String> prop : objProps) {
                // ★ 展开 owl:unionOf / owl:intersectionOf 等 OWL class expression，
                //    为每个 domain×range 组合创建独立的 LinkType，避免前端图谱显示
                //    "[ owl:unionOf (:A :B) ]" 而非实际类名。
                Object domainRaw = ((java.util.Map<?, ?>) prop).get("domain");
                Object rangeRaw = ((java.util.Map<?, ?>) prop).get("range");
                java.util.List<String> domains = extractIrisFromDomainExpr(domainRaw);
                java.util.List<String> ranges = extractIrisFromDomainExpr(rangeRaw);

                // 如果 unionOf 展开失败（不是 owl:unionOf 格式），回退到原始值
                if (domains.isEmpty()) {
                    String fallback = cleanIri(safeString(domainRaw));
                    if (fallback != null && !fallback.isBlank()) domains.add(fallback);
                }
                if (ranges.isEmpty()) {
                    String fallback = cleanIri(safeString(rangeRaw));
                    if (ranges != null && fallback != null && !fallback.isBlank()) ranges.add(fallback);
                }

                // 如果 domain/range 仍为空，至少创建一个 LinkType（保留原始值以便诊断）
                if (domains.isEmpty()) domains.add("");
                if (ranges.isEmpty()) ranges.add("");

                for (String domain : domains) {
                    for (String range : ranges) {
                        LinkType linkType = new LinkType();
                        linkType.setOntologyId(id);
                        linkType.setIri(cleanIri(getWithFallback(prop, "iri", "id")));
                        linkType.setLabel(getWithFallback(prop, "label", "name"));
                        linkType.setSourceType(domain);
                        linkType.setTargetType(range);
                        linkType.setDescription(safeString(((java.util.Map<?, ?>) prop).get("description")));
                        ontologyService.createLinkType(id, linkType);
                    }
                }
            }
        }
        // 保存数据属性：按 domain 分组后写入对应 ObjectType 的 propertiesJson
        if (dataProps != null && !dataProps.isEmpty()) {
            Map<String, List<Map<String, String>>> propsByDomain = new LinkedHashMap<>();
            for (Map<String, String> prop : dataProps) {
                Object domainRaw = ((java.util.Map<?, ?>) prop).get("domain");
                java.util.List<String> domains = extractIrisFromDomainExpr(domainRaw);
                for (String domain : domains) {
                    propsByDomain.computeIfAbsent(domain, k -> new ArrayList<>()).add(prop);
                }
            }
            List<ObjectType> objectTypes = ontologyService.findObjectTypes(id);
            // 精确匹配 + 三层 fallback（与 /import 端点逻辑一致，防止属性因 domain 不匹配被丢弃）
            attachDataPropsToClasses(propsByDomain, objectTypes);
            for (ObjectType objType : objectTypes) {
                if (objType.getPropertiesJson() != null) {
                    ontologyService.updateObjectType(objType.getId(), objType);
                }
            }
        }
        // ★ 保存业务规则 — 先从 TTL 解析 SWRL 规则，避免 JSON 中的空壳 metadata 被误存为 CONDITION
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> rules = (List<Map<String, Object>>) extractionData.get("rules");

        // 1. 用 Jena 解析 TTL，收集其中 swrl:Imp 的 label 和 IRI，用于判断 JSON rule 是否为 SWRL metadata stub
        java.util.Map<String, String> swrlImpLabelByIri = new java.util.LinkedHashMap<>();   // localName → label
        java.util.Set<String> swrlImpLabels = new java.util.HashSet<>();                     // all labels
        try {
            Model ttlModel = ModelFactory.createDefaultModel();
            ttlModel.read(new java.io.ByteArrayInputStream(ttlContent.getBytes(StandardCharsets.UTF_8)), null, "TURTLE");
            String swrlNs = "http://www.w3.org/2003/11/swrl#";
            java.util.List<org.apache.jena.rdf.model.Resource> imps = ttlModel
                    .listResourcesWithProperty(org.apache.jena.vocabulary.RDF.type, ttlModel.getResource(swrlNs + "Imp"))
                    .toList();
            for (org.apache.jena.rdf.model.Resource imp : imps) {
                String localName = extractLocalName(imp.getURI());
                String label = getStringValue(ttlModel, imp, "http://www.w3.org/2000/01/rdf-schema#label");
                if (label != null && !label.isBlank()) {
                    swrlImpLabelByIri.put(localName, label);
                    swrlImpLabels.add(label);
                }
            }
        } catch (Exception e) {
            log.warn("TTL 预解析 SWRL 规则失败（不影响保存）: {}", e.getMessage());
        }

        // 2. 从 TTL 中直接提取 SWRL 规则到 DB（先于 JSON rules 处理，确保 SWRL 规则被正确创建）
        int ttlSwrlCount = 0;
        try {
            Model ttlModelForSwrl = ModelFactory.createDefaultModel();
            ttlModelForSwrl.read(new java.io.ByteArrayInputStream(ttlContent.getBytes(StandardCharsets.UTF_8)), null, "TURTLE");
            ttlSwrlCount = extractAndPersistSwrlRules(ttlModelForSwrl, id);
            if (ttlSwrlCount > 0) {
                log.info("从 TTL 提取了 {} 条 SWRL 规则到 DB", ttlSwrlCount);
            }
        } catch (Exception e) {
            log.warn("从 TTL 提取 SWRL 规则失败（不影响保存）: {}", e.getMessage());
        }

        // 3. 处理 JSON rules：跳过 metadata-only stub，只保存有实际 condition/action/swrlRule 的规则
        if (rules != null) {
            java.util.Set<String> usedRuleNames = new java.util.HashSet<>();
            // 将已从 TTL 提取的 SWRL 规则名称加入去重集合
            try {
                java.util.List<BusinessRule> existingSwrlRules = ruleService.findByOntologyId(id).stream()
                        .filter(r -> r.getType() == BusinessRule.RuleType.SWRL)
                        .collect(java.util.stream.Collectors.toList());
                for (BusinessRule r : existingSwrlRules) {
                    if (r.getName() != null) usedRuleNames.add(r.getName());
                }
            } catch (Exception ignored) {}

            for (Map<String, Object> rule : rules) {
                String ruleName = resolveRuleName(rule);
                String ruleIri = rule.get("iri") != null ? rule.get("iri").toString().replaceFirst("^:", "") : "";
                String swrlRuleField = rule.get("swrlRule") != null ? rule.get("swrlRule").toString() : null;
                String jsonDescription = rule.get("description") != null ? rule.get("description").toString() : null;
                Object conditionObj = rule.get("condition");
                Object actionObj = rule.get("action");
                boolean hasSwrlExpr = swrlRuleField != null && !swrlRuleField.isBlank();
                boolean hasCondition = conditionObj != null && !conditionObj.toString().isBlank();
                boolean hasAction = actionObj != null && !actionObj.toString().isBlank();

                // ★ 如果 JSON rule 没有任何实质内容（无 swrlRule、无 condition、无 action），
                //    它就是 SWRL metadata stub — 当 TTL 已成功提取 SWRL 规则时跳过（避免重复）
                //    当 TTL 解析失败（ttlSwrlCount==0）时，metadata 是唯一来源，必须保存
                if (!hasSwrlExpr && !hasCondition && !hasAction) {
                    if (ttlSwrlCount > 0) {
                        log.debug("跳过 SWRL metadata stub: name={}, iri={}", ruleName, ruleIri);
                        continue;
                    }
                    log.info("TTL未提取到SWRL规则，从JSON metadata保存规则: name={}, iri={}", ruleName, ruleIri);
                }

                // 以下为原有逻辑：保存有实际内容的非 stub 规则
                String uniqueName = ruleName;
                int counter = 2;
                while (usedRuleNames.contains(uniqueName)) {
                    uniqueName = ruleName + "-" + counter;
                    counter++;
                }
                usedRuleNames.add(uniqueName);
                String ruleType = rule.getOrDefault("type", "CONDITION").toString();
                if (hasSwrlExpr) {
                    ruleType = "SWRL";
                }
                // 本体提取的规则元数据（无swrlExpr也无condition/action）必然是SWRL规则
                if (!hasSwrlExpr && !hasCondition && !hasAction && (ruleName != null || !ruleIri.isEmpty())) {
                    ruleType = "SWRL";
                }

                // ★ SWRL 规则表达式回退策略：
                //   当 JSON 未提供 swrlRule 且 TTL 解析失败时，使用自然语言描述作为 SWRL 表达式，
                //   避免 SWRL 列在前端显示为空。
                String effectiveSwrlRule = swrlRuleField;
                if ((effectiveSwrlRule == null || effectiveSwrlRule.isBlank())
                        && "SWRL".equalsIgnoreCase(ruleType)
                        && jsonDescription != null && !jsonDescription.isBlank()) {
                    effectiveSwrlRule = jsonDescription;
                    log.info("SWRL规则使用描述作为表达式回退: name={}, desc前80字={}",
                            uniqueName, jsonDescription.substring(0, Math.min(80, jsonDescription.length())));
                }

                String involvedClasses = null;
                Object involvedClassesObj = rule.get("involvedClasses");
                if (involvedClassesObj != null) {
                    involvedClasses = involvedClassesObj instanceof String ? involvedClassesObj.toString() : objectMapper.writeValueAsString(involvedClassesObj);
                }
                String involvedDataProperties = null;
                Object involvedDataPropertiesObj = rule.get("involvedDataProperties");
                if (involvedDataPropertiesObj != null) {
                    involvedDataProperties = involvedDataPropertiesObj instanceof String ? involvedDataPropertiesObj.toString() : objectMapper.writeValueAsString(involvedDataPropertiesObj);
                }
                BusinessRule businessRule = BusinessRule.builder()
                        .ontologyId(id)
                        .name(uniqueName)
                        .description(jsonDescription)
                        .type(BusinessRule.RuleType.valueOf(ruleType.toUpperCase()))
                        .conditionJson(rule.get("condition") != null ? rule.get("condition").toString() : null)
                        .actionJson(rule.get("action") != null ? rule.get("action").toString() : null)
                        .swrlRule(effectiveSwrlRule)
                        .involvedClasses(involvedClasses)
                        .involvedDataProperties(involvedDataProperties)
                        .source("自动提取")
                        .confidence(1.0)
                        .status(BusinessRule.RuleStatus.DRAFT)
                        .build();
                ruleService.create(businessRule);
            }
        }

        // 4. ★ 用 JSON rules 中的 label/description 丰富已从 TTL 提取的 SWRL 规则
        if (rules != null && ttlSwrlCount > 0) {
            try {
                java.util.List<BusinessRule> swrlRules = ruleService.findByOntologyId(id).stream()
                        .filter(r -> r.getType() == BusinessRule.RuleType.SWRL)
                        .collect(java.util.stream.Collectors.toList());
                for (BusinessRule swrlRule : swrlRules) {
                    for (Map<String, Object> jsonRule : rules) {
                        String jsonLabel = resolveRuleName(jsonRule);
                        String jsonIri = jsonRule.get("iri") != null ? jsonRule.get("iri").toString().replaceFirst("^:", "") : "";
                        // 通过 IRI 或 label 匹配
                        boolean matched = false;
                        if (!jsonIri.isEmpty() && swrlRule.getName() != null) {
                            String swrlName = swrlRule.getName();
                            // 精确匹配 JSON iri
                            if (swrlName.equals(jsonIri) || swrlName.equals(jsonLabel)) {
                                matched = true;
                            }
                            // 匹配 TTL 中的 rdfs:label
                            String ttlLabel = swrlImpLabelByIri.get(jsonIri);
                            if (ttlLabel != null && swrlName.equals(ttlLabel)) {
                                matched = true;
                            }
                        }
                        if (!matched && jsonLabel != null && jsonLabel.equals(swrlRule.getName())) {
                            matched = true;
                        }
                        if (matched) {
                            // 补充描述：JSON description 有完整业务逻辑+参考来源，始终覆盖 TTL rdfs:comment 的短名
                            Object jsonDesc = jsonRule.get("description");
                            if (jsonDesc != null && !jsonDesc.toString().isBlank()) {
                                String newDesc = jsonDesc.toString();
                                log.info("规则描述覆盖: name={}, TTL原描述={}, JSON新描述前80字={}",
                                        swrlRule.getName(),
                                        swrlRule.getDescription() != null ? swrlRule.getDescription().substring(0, Math.min(60, swrlRule.getDescription().length())) : "null",
                                        newDesc.substring(0, Math.min(80, newDesc.length())));
                                swrlRule.setDescription(newDesc);
                            } else {
                                log.warn("规则描述未覆盖: name={}, JSON description 为空或缺失", swrlRule.getName());
                            }
                            // 用 JSON 中的中文 label 作为名称
                            if (jsonLabel != null && !jsonLabel.isBlank() && !jsonLabel.equals(swrlRule.getName())) {
                                swrlRule.setName(jsonLabel);
                            }
                            ruleService.update(swrlRule.getId(), swrlRule);
                            break;
                        }
                    }
                }
                log.info("已用 JSON metadata 丰富 {} 条 SWRL 规则", swrlRules.size());
            } catch (Exception e) {
                log.warn("丰富 SWRL 规则 metadata 失败（不影响保存）: {}", e.getMessage());
            }
        }

        // 从数据库数据重新生成 TTL，确保 TTL 文件与数据库一致（LLM 生成的 TTL 可能遗漏 rdfs:subClassOf 等）
        syncTtlFromDb(id);

        return Result.success(saved);
    }

    @GetMapping("/{id}/ttl")
    @Operation(summary = "获取本体 TTL 内容")
    public Result<String> getTtlContent(@PathVariable String id) throws IOException {
        Ontology ontology = ontologyService.findById(id);
        if (ontology.getTtlPath() == null || !Files.exists(Paths.get(ontology.getTtlPath()))) {
            return Result.success("");
        }
        return Result.success(Files.readString(Paths.get(ontology.getTtlPath()), StandardCharsets.UTF_8));
    }

    @PutMapping("/{id}/ttl")
    @Operation(summary = "更新本体 TTL 内容")
    public Result<Void> updateTtlContent(@PathVariable String id, @RequestBody Map<String, String> body) {
        Ontology ontology = ontologyService.findById(id);
        String content = body.get("content");
        if (content == null) {
            return Result.error("content 字段不能为空");
        }
        // 确定 TTL 文件存储路径
        Path ttlPath;
        if (ontology.getTtlPath() != null && !ontology.getTtlPath().isBlank()) {
            ttlPath = Paths.get(ontology.getTtlPath());
        } else {
            ttlPath = Paths.get(storageBaseDir, "ttl", id + ".ttl");
            ontology.setTtlPath(ttlPath.toString());
            ontologyService.update(id, ontology);
        }
        try {
            Files.createDirectories(ttlPath.getParent());
            Files.writeString(ttlPath, content, StandardCharsets.UTF_8);
            log.info("TTL 文件已更新: {} ({} bytes)", id, content.length());
            // 同步更新 TDB2 中的三元组数据
            try {
                reasoningService.loadTtlContent(content, id);
                ontology.setInstantiated(true);
                ontologyService.update(id, ontology);
                log.info("TTL 更新后已重新加载到TDB2: id={}", id);
            } catch (Exception tdb2Ex) {
                log.warn("TTL 已更新但重新加载TDB2失败: id={}, error={}", id, tdb2Ex.getMessage());
            }
            // 从 TTL 文件同步数据库记录（ObjectType / LinkType / propertiesJson）
            syncDbFromTtl(id);
            // 从 DB 重新生成 TTL 文件，消除重复声明并确保关系完整
            syncTtlFromDb(id);
            return Result.success();
        } catch (IOException e) {
            log.error("TTL 文件写入失败: path={}, error={}", ttlPath, e.getMessage(), e);
            return Result.error("TTL 文件保存失败: " + e.getMessage());
        }
    }

    @PostMapping("/{id}/hermit-reason")
    @Operation(summary = "HermiT OWL 2 DL 推理验证", description = "使用 HermiT 推理器执行完整语义推理：一致性检查、分类、发现隐含关系")
    public Result<Map<String, Object>> hermitReason(@PathVariable String id) {
        try {
            Ontology ontology = ontologyService.findById(id);
            var objectTypes = ontologyService.findObjectTypes(id);
            var linkTypes = ontologyService.findLinkTypes(id);

            ReasoningService.HermiTCheckResult result =
                    reasoningService.checkConsistencyWithHermiT(ontology, objectTypes, linkTypes);

            Map<String, Object> response = new LinkedHashMap<>();
            response.put("consistent", result.isConsistent());
            response.put("inconsistencies", result.getInconsistencies());
            response.put("unsatisfiableClasses", result.getUnsatisfiableClasses());
            response.put("inferredHierarchy", result.getInferredHierarchy());
            response.put("inferredPropertyHierarchy", result.getInferredPropertyHierarchy());
            response.put("equivalentClasses", result.getEquivalentClasses());
            response.put("disjointClasses", result.getDisjointClasses());
            response.put("inferredSuperClasses", result.getInferredSuperClasses());
            response.put("totalIndividuals", result.getTotalIndividuals());
            response.put("inferredIndividualTypes", result.getInferredIndividualTypes());
            response.put("classInstances", result.getClassInstances());
            response.put("propertyCharacteristics", result.getPropertyCharacteristics());
            response.put("inverseProperties", result.getInverseProperties());
            response.put("equivalentProperties", result.getEquivalentProperties());
            response.put("inferredDataPropertyHierarchy", result.getInferredDataPropertyHierarchy());
            response.put("dataPropertyDomains", result.getDataPropertyDomains());
            response.put("swrlRuleResults", result.getSwrlRuleResults());
            response.put("totalClasses", result.getTotalClasses());
            response.put("totalObjectProperties", result.getTotalObjectProperties());
            response.put("totalDatatypeProperties", result.getTotalDatatypeProperties());
            response.put("totalAxioms", result.getTotalAxioms());
            response.put("reasonerName", result.getReasonerName());
            response.put("reasonerVersion", result.getReasonerVersion());
            response.put("preparationTimeMs", result.getPreparationTimeMs());
            return Result.success(response);
        } catch (Exception e) {
            log.error("HermiT 推理失败", e);
            return Result.error("HermiT 推理失败: " + e.getMessage());
        }
    }

    @PostMapping("/{id}/pellet-reason")
    @Operation(summary = "Pellet OWL 2 DL 推理验证", description = "使用 Pellet 推理器执行完整语义推理：一致性检查、分类、发现隐含关系")
    public Result<Map<String, Object>> pelletReason(@PathVariable String id) {
        try {
            Ontology ontology = ontologyService.findById(id);
            var objectTypes = ontologyService.findObjectTypes(id);
            var linkTypes = ontologyService.findLinkTypes(id);

            ReasoningService.HermiTCheckResult result =
                    reasoningService.checkConsistencyWithPellet(ontology, objectTypes, linkTypes);

            Map<String, Object> response = new LinkedHashMap<>();
            response.put("consistent", result.isConsistent());
            response.put("inconsistencies", result.getInconsistencies());
            response.put("unsatisfiableClasses", result.getUnsatisfiableClasses());
            response.put("inferredHierarchy", result.getInferredHierarchy());
            response.put("inferredPropertyHierarchy", result.getInferredPropertyHierarchy());
            response.put("equivalentClasses", result.getEquivalentClasses());
            response.put("disjointClasses", result.getDisjointClasses());
            response.put("inferredSuperClasses", result.getInferredSuperClasses());
            response.put("totalIndividuals", result.getTotalIndividuals());
            response.put("inferredIndividualTypes", result.getInferredIndividualTypes());
            response.put("classInstances", result.getClassInstances());
            response.put("propertyCharacteristics", result.getPropertyCharacteristics());
            response.put("inverseProperties", result.getInverseProperties());
            response.put("equivalentProperties", result.getEquivalentProperties());
            response.put("inferredDataPropertyHierarchy", result.getInferredDataPropertyHierarchy());
            response.put("dataPropertyDomains", result.getDataPropertyDomains());
            response.put("swrlRuleResults", result.getSwrlRuleResults());
            response.put("totalClasses", result.getTotalClasses());
            response.put("totalObjectProperties", result.getTotalObjectProperties());
            response.put("totalDatatypeProperties", result.getTotalDatatypeProperties());
            response.put("totalAxioms", result.getTotalAxioms());
            response.put("reasonerName", result.getReasonerName());
            response.put("reasonerVersion", result.getReasonerVersion());
            response.put("preparationTimeMs", result.getPreparationTimeMs());
            return Result.success(response);
        } catch (Exception e) {
            log.error("Pellet 推理失败", e);
            return Result.error("Pellet 推理失败: " + e.getMessage());
        }
    }

    @PostMapping("/{id}/sparql")
    @Operation(summary = "执行 SPARQL 查询", description = "对已实例化的本体执行 SPARQL 查询（TDB2 持久化存储）")
    public Result<Map<String, Object>> executeSparql(@PathVariable String id, @RequestBody Map<String, String> body) {
        String query = body.get("query");
        if (query == null || query.isBlank()) {
            return Result.success(Map.of("variables", List.of(), "bindings", List.of()));
        }

        // 解析 TDB2 命名图名称：支持 instanceId、jenaModelName、ontologyId、taskId 多种形式
        // 0. 先按 instanceId 查找（新设计：租户+实例 → graphUri）
        // 1. 再按 jenaModelName 查找（前端 DataBrowser 传入的是 jenaModelName）
        // 2. 再按 ontologyId 查找（API 调用可能传入 ontologyId）
        // 3. 最后直接使用入参作为命名图名称（兼容旧数据和 TBox 查询）
        String effectiveModelName = null;
        Optional<InstantiationTask> completedTask = Optional.empty();

        // 尝试作为 instanceId 查找
        Optional<OntologyInstance> instanceOpt = instanceRepository.findById(id);
        if (instanceOpt.isPresent()) {
            effectiveModelName = instanceOpt.get().getGraphUri();
            log.info("通过 instanceId 找到本体实例 {} (tenant={})，使用 graphUri: {}",
                    instanceOpt.get().getId(), instanceOpt.get().getTenantId(), effectiveModelName);
        }

        // 尝试作为 jenaModelName 查找
        if (effectiveModelName == null) {
            List<InstantiationTask> tasksByJena = instantiationTaskRepository.findByJenaModelName(id);
            completedTask = tasksByJena.stream()
                    .filter(t -> t.getStatus() == InstantiationTask.TaskStatus.COMPLETED)
                    .findFirst();
            if (completedTask.isPresent()) {
                effectiveModelName = completedTask.get().getJenaModelName();
                log.info("通过 jenaModelName 找到实例化任务 {} (v{})，使用 TDB2 模型: {}",
                        completedTask.get().getId(), completedTask.get().getVersion(), effectiveModelName);
            }
        }

        // 尝试作为 ontologyId 查找
        if (effectiveModelName == null) {
            List<InstantiationTask> tasks = instantiationTaskRepository.findByOntologyId(id);
            completedTask = tasks.stream()
                    .filter(t -> t.getStatus() == InstantiationTask.TaskStatus.COMPLETED)
                    .max(Comparator.comparing(InstantiationTask::getVersion,
                            Comparator.nullsLast(Comparator.reverseOrder())));
            if (completedTask.isPresent()) {
                effectiveModelName = completedTask.get().getJenaModelName();
                if (effectiveModelName == null || effectiveModelName.isBlank()) {
                    effectiveModelName = "inst_" + completedTask.get().getId();
                }
                log.info("通过 ontologyId 找到实例化任务 {} (v{})，使用 TDB2 模型: {}",
                        completedTask.get().getId(), completedTask.get().getVersion(), effectiveModelName);
            }
        }

        // 未找到实例化任务时，直接使用入参作为命名图名称
        if (effectiveModelName == null) {
            effectiveModelName = id;
            log.info("未找到实例/任务，直接使用入参作为 TDB2 命名图: {}", id);
        }

        ReasoningService.SparqlQueryResult queryResult = reasoningService.executeSparqlQuery(effectiveModelName, query);

        // 如果查询失败（模型为空），尝试自动加载 TTL 到 TDB2
        // 注意：如果请求是通过 instanceId 进来的，说明已有实例管理其数据生命周期，
        // 不应自动重载（用户可能已显式清空数据，自动重载会破坏清空操作的结果）
        if (!queryResult.isSuccess() && queryResult.getErrorMessage() != null
                && queryResult.getErrorMessage().contains("本体未加载到 TDB2")) {
            if (instanceOpt.isPresent()) {
                log.info("实例 {} (graphUri={}) 的 TDB2 命名图为空（数据可能已被清空），跳过自动加载",
                        instanceOpt.get().getId(), effectiveModelName);
            } else {
                // 解析真正的本体 ID：id 可能是 jenaModelName 或 ontologyId
                String ontologyId = id;
                if (completedTask.isPresent() && completedTask.get().getOntologyId() != null
                        && !completedTask.get().getOntologyId().isBlank()) {
                    ontologyId = completedTask.get().getOntologyId();
                }
                log.warn("本体 {} 在命名图 {} 中未找到，尝试自动加载 TTL 到 TDB2", ontologyId, effectiveModelName);
                try {
                    Ontology ont = ontologyService.findById(ontologyId);
                    if (ont.getTtlPath() != null && Files.exists(Paths.get(ont.getTtlPath()))) {
                        String ttl = Files.readString(Paths.get(ont.getTtlPath()), StandardCharsets.UTF_8);
                        reasoningService.loadTtlContent(ttl, effectiveModelName);
                        ont.setInstantiated(true);
                        ontologyService.update(ontologyId, ont);
                        log.info("本体自动加载到TDB2成功，重新执行查询: ontologyId={}, modelName={}", ontologyId, effectiveModelName);
                        queryResult = reasoningService.executeSparqlQuery(effectiveModelName, query);
                    }
                } catch (NotFoundException e) {
                    log.warn("本体自动加载TDB2失败，本体未找到: ontologyId={}", ontologyId);
                } catch (Exception autoEx) {
                    log.warn("本体自动加载TDB2失败: ontologyId={}, error={}", ontologyId, autoEx.getMessage());
                }
            }
        }

        Map<String, Object> result = new HashMap<>();
        result.put("variables", queryResult.getVariables());
        result.put("bindings", queryResult.getResults());
        result.put("success", queryResult.isSuccess());
        if (!queryResult.isSuccess()) {
            result.put("error", queryResult.getErrorMessage());
        }
        return Result.success(result);
    }

    @PostMapping("/{id}/verify-sparql")
    @Operation(summary = "NeSyS 符号验证 SPARQL", description = "基于 OWL TBox 验证 SPARQL 语义正确性，返回错误列表和 TBox 上下文用于 LLM 修正")
    public Result<Map<String, Object>> verifySparql(@PathVariable String id, @RequestBody Map<String, String> body) {
        String sparql = body.get("sparql");
        if (sparql == null || sparql.isBlank()) {
            return Result.success(Map.of("passed", true, "errors", List.of(), "warnings", List.of(),
                    "schemaContext", "", "issues", List.of()));
        }

        // Resolve ontology TTL
        String ttl = null;
        Optional<OntologyInstance> instanceOpt = instanceRepository.findById(id);
        if (instanceOpt.isPresent()) {
            Ontology ont = ontologyService.findById(instanceOpt.get().getOntologyId());
            try {
                if (ont.getTtlPath() != null && Files.exists(Paths.get(ont.getTtlPath()))) {
                    ttl = Files.readString(Paths.get(ont.getTtlPath()), StandardCharsets.UTF_8);
                }
            } catch (Exception e) {
                log.warn("读取本体 TTL 失败: {}", e.getMessage());
            }
        }

        if (ttl == null) {
            // Try loading from ontology directly
            try {
                Ontology ont = ontologyService.findById(id);
                if (ont.getTtlPath() != null && Files.exists(Paths.get(ont.getTtlPath()))) {
                    ttl = Files.readString(Paths.get(ont.getTtlPath()), StandardCharsets.UTF_8);
                }
            } catch (Exception e) {
                log.warn("读取本体 TTL 失败 (by ontologyId): {}", e.getMessage());
            }
        }

        if (ttl == null) {
            return Result.success(Map.of("passed", false, "errors", List.of(Map.of(
                    "severity", "ERROR", "type", "no_tbox",
                    "message", "无法加载本体 TBox，请确认本体已发布且 TTL 文件存在",
                    "location", "entire query")),
                    "warnings", List.of(),
                    "schemaContext", ""));
        }

        // Run verification
        try {
            com.ontologybuilder.oxirs.NeuroSymbolicVerifier verifier = new com.ontologybuilder.oxirs.NeuroSymbolicVerifier();
            org.semanticweb.owlapi.model.OWLOntologyManager owlManager =
                    org.semanticweb.owlapi.apibinding.OWLManager.createOWLOntologyManager();
            org.semanticweb.owlapi.model.OWLOntology owlOntology = owlManager.loadOntologyFromOntologyDocument(
                    new java.io.ByteArrayInputStream(ttl.getBytes(StandardCharsets.UTF_8)));

            com.ontologybuilder.oxirs.VerificationResult vResult = verifier.verify(sparql, owlOntology, null);

            Map<String, Object> response = new HashMap<>();
            response.put("passed", vResult.passed());
            response.put("errors", vResult.getErrors().stream()
                    .map(e -> Map.of("severity", e.getSeverity().name(),
                            "type", e.getType(),
                            "message", e.getMessage(),
                            "location", e.getLocation()))
                    .collect(java.util.stream.Collectors.toList()));
            response.put("warnings", vResult.getWarnings().stream()
                    .map(w -> Map.of("severity", w.getSeverity().name(),
                            "type", w.getType(),
                            "message", w.getMessage(),
                            "location", w.getLocation()))
                    .collect(java.util.stream.Collectors.toList()));
            response.put("schemaContext", vResult.getSchemaContext() != null
                    ? vResult.getSchemaContext() : "");
            return Result.success(response);
        } catch (Exception e) {
            log.error("SPARQL 验证失败: {}", e.getMessage(), e);
            return Result.success(Map.of("passed", false, "errors", List.of(Map.of(
                    "severity", "ERROR", "type", "verification_error",
                    "message", "验证过程出错: " + e.getMessage(),
                    "location", "entire query")),
                    "warnings", List.of(), "schemaContext", ""));
        }
    }

    @PostMapping("/{id}/apply-update")
    @Operation(summary = "知识更新：应用 TTL 片段到知识图谱",
               description = "将 LLM 提取的结构化事实以 TTL 片段形式追加到 TDB2，支持 validate 模式（仅校验）和 apply 模式（校验+写入）")
    public Result<Map<String, Object>> applyUpdate(@PathVariable String id, @RequestBody Map<String, Object> body) {
        String ttlSnippet = (String) body.get("ttlSnippet");
        String mode = (String) body.getOrDefault("mode", "validate"); // "validate" | "apply"
        @SuppressWarnings("unchecked")
        List<Map<String, String>> facts = (List<Map<String, String>>) body.get("facts");

        if ((ttlSnippet == null || ttlSnippet.isBlank()) && (facts == null || facts.isEmpty())) {
            return Result.error("ttlSnippet 或 facts 不能为空");
        }

        // Resolve target graph URI
        String graphUri = null;
        Optional<OntologyInstance> instanceOpt = instanceRepository.findById(id);
        if (instanceOpt.isPresent()) {
            graphUri = instanceOpt.get().getGraphUri();
            if (graphUri == null || graphUri.isBlank()) {
                graphUri = "inst_" + instanceOpt.get().getId();
            }
        } else {
            // Try as ontology id
            List<InstantiationTask> tasks = instantiationTaskRepository.findByOntologyId(id);
            Optional<InstantiationTask> completedTask = tasks.stream()
                    .filter(t -> t.getStatus() == InstantiationTask.TaskStatus.COMPLETED)
                    .max(Comparator.comparing(InstantiationTask::getVersion,
                            Comparator.nullsLast(Comparator.reverseOrder())));
            if (completedTask.isPresent()) {
                graphUri = completedTask.get().getJenaModelName();
                if (graphUri == null || graphUri.isBlank()) {
                    graphUri = "inst_" + completedTask.get().getId();
                }
            } else {
                graphUri = id;
            }
        }
        log.info("知识更新: mode={}, graphUri={}, input type={}",
                mode, graphUri, ttlSnippet != null ? "ttl" : "facts");

        // Step 1: Validate against TBox
        Map<String, Object> validationResult = new HashMap<>();
        boolean passed = true;
        // TBox property localName → fullIRI mapping, built during validation, used in apply phase
        Map<String, String> tboxPropertyMap = new HashMap<>();
        try {
            // Load ontology for validation
            String ontologyIdForValidation = id;
            if (instanceOpt.isPresent()) {
                ontologyIdForValidation = instanceOpt.get().getOntologyId();
            }
            Ontology ont = ontologyService.findById(ontologyIdForValidation);
            if (ont != null && ont.getTtlPath() != null && Files.exists(Paths.get(ont.getTtlPath()))) {
                String ttl = Files.readString(Paths.get(ont.getTtlPath()), StandardCharsets.UTF_8);
                com.ontologybuilder.oxirs.NeuroSymbolicVerifier verifier =
                        new com.ontologybuilder.oxirs.NeuroSymbolicVerifier();
                org.semanticweb.owlapi.model.OWLOntologyManager owlManager =
                        org.semanticweb.owlapi.apibinding.OWLManager.createOWLOntologyManager();
                org.semanticweb.owlapi.model.OWLOntology owlOntology =
                        owlManager.loadOntologyFromOntologyDocument(
                                new java.io.ByteArrayInputStream(ttl.getBytes(StandardCharsets.UTF_8)));

                // Build TBox property name → IRI mapping for namespace normalization
                for (org.semanticweb.owlapi.model.OWLDataProperty dp : owlOntology.getDataPropertiesInSignature()) {
                    String iri = dp.getIRI().toString();
                    String localName = iri.contains("#") ? iri.substring(iri.lastIndexOf('#') + 1)
                            : iri.substring(iri.lastIndexOf('/') + 1);
                    tboxPropertyMap.putIfAbsent(localName, iri);
                }
                for (org.semanticweb.owlapi.model.OWLObjectProperty op : owlOntology.getObjectPropertiesInSignature()) {
                    String iri = op.getIRI().toString();
                    String localName = iri.contains("#") ? iri.substring(iri.lastIndexOf('#') + 1)
                            : iri.substring(iri.lastIndexOf('/') + 1);
                    tboxPropertyMap.putIfAbsent(localName, iri);
                }

                // Convert facts to TripleFact list for validation
                if (facts != null && !facts.isEmpty()) {
                    List<com.ontologybuilder.oxirs.NeuroSymbolicVerifier.TripleFact> tripleFacts = new ArrayList<>();
                    for (Map<String, String> f : facts) {
                        tripleFacts.add(new com.ontologybuilder.oxirs.NeuroSymbolicVerifier.TripleFact(
                                f.get("subjectIri"),
                                f.get("predicateIri"),
                                f.get("objectValue"),
                                f.get("objectType"),
                                f.get("subjectClassIri")));
                    }
                    com.ontologybuilder.oxirs.VerificationResult vResult =
                            verifier.validateTripleFacts(tripleFacts, owlOntology);

                    passed = vResult.passed();
                    validationResult.put("passed", passed);
                    validationResult.put("errors", vResult.getErrors().stream()
                            .map(e -> Map.of("severity", e.getSeverity().name(),
                                    "type", e.getType(), "message", e.getMessage(),
                                    "location", e.getLocation()))
                            .collect(java.util.stream.Collectors.toList()));
                    validationResult.put("warnings", vResult.getWarnings().stream()
                            .map(w -> Map.of("severity", w.getSeverity().name(),
                                    "type", w.getType(), "message", w.getMessage(),
                                    "location", w.getLocation()))
                            .collect(java.util.stream.Collectors.toList()));
                } else {
                    validationResult.put("passed", true);
                    validationResult.put("errors", List.of());
                    validationResult.put("warnings", List.of());
                }

            } else {
                log.warn("知识更新: 无法加载 TBox 进行验证，跳过校验");
                validationResult.put("passed", true);
                validationResult.put("errors", List.of());
                validationResult.put("warnings",
                        List.of(Map.of("severity", "WARNING", "type", "no_tbox",
                                "message", "无法加载 TBox，跳过语义校验")));
            }
        } catch (Exception e) {
            log.warn("知识更新 TBox 验证异常: {}", e.getMessage());
            validationResult.put("passed", true); // Don't block on validation failure
            validationResult.put("errors", List.of());
            validationResult.put("warnings",
                    List.of(Map.of("severity", "WARNING", "type", "validation_error",
                            "message", "TBox 验证异常: " + e.getMessage())));
        }

        if ("validate".equals(mode)) {
            return Result.success(Map.of(
                    "mode", "validate",
                    "validation", validationResult,
                    "applied", false));
        }

        // Step 2: Apply update (mode=apply, only if validation passed or has no errors)
        boolean hasErrors = !validationResult.getOrDefault("passed", true).equals(true);
        if (hasErrors) {
            return Result.success(Map.of(
                    "mode", "apply",
                    "validation", validationResult,
                    "applied", false,
                    "reason", "TBox 验证未通过，拒绝写入。请修正错误后重试，或使用 mode=validate 先检查。"));
        }

        try {
            String ttlToApply = ttlSnippet;
            if (ttlToApply == null && facts != null && !facts.isEmpty()) {
                // 分离 delete 和 add 事实：LLM 可能在单个 fact 上标记 "operation": "delete"
                List<Map<String, String>> deleteFacts = new ArrayList<>();
                List<Map<String, String>> addFacts = new ArrayList<>();
                for (Map<String, String> f : facts) {
                    if ("delete".equals(f.get("operation"))) {
                        deleteFacts.add(f);
                    } else {
                        addFacts.add(f);
                    }
                }
                if (!deleteFacts.isEmpty()) {
                    // 构建删除 TTL 并执行
                    StringBuilder delSb = new StringBuilder();
                    for (Map<String, String> f : deleteFacts) {
                        String s = f.get("subjectIri"), p = f.get("predicateIri"), o = f.get("objectValue");
                        if (s == null || p == null || o == null) continue;
                        if ("iri".equals(f.get("objectType"))) {
                            delSb.append("<").append(s).append("> <").append(p).append("> <").append(o).append("> .\n");
                        } else {
                            String esc = o.replace("\\", "\\\\").replace("\"", "\\\"");
                            delSb.append("<").append(s).append("> <").append(p).append("> \"").append(esc).append("\" .\n");
                        }
                    }
                    if (delSb.length() > 0) {
                        log.info("知识更新: 显式删除 {} 条三元组", deleteFacts.size());
                        reasoningService.deleteTtlSnippet(delSb.toString(), graphUri);
                    }
                }
                facts = addFacts; // 后续只处理 add 事实
                if (facts.isEmpty()) {
                    return Result.success(Map.of("mode", "apply", "validation", validationResult,
                            "applied", true, "tripleCount", 0, "graphUri", graphUri));
                }

                // Entity resolution: 基于 facts 已有属性动态查找已有实例 IRI，复用而非新增
                // 完全通用：不硬编码任何属性名，根据 LLM 提供的任意属性值组合进行匹配
                Map<String, Map<String, String>> subjToIdProps = new HashMap<>();
                Map<String, String> subjToClassIri = new HashMap<>();
                for (Map<String, String> f : facts) {
                    String subj = f.get("subjectIri");
                    if (subj == null) continue;
                    subjToIdProps.computeIfAbsent(subj, k -> new HashMap<>())
                            .put(f.get("predicateIri"), f.get("objectValue"));
                    String cls = f.get("subjectClassIri");
                    if (cls != null) subjToClassIri.putIfAbsent(subj, cls);
                }
                for (Map.Entry<String, Map<String, String>> entry : subjToIdProps.entrySet()) {
                    Map<String, String> idProps = entry.getValue();
                    String clsIri = subjToClassIri.get(entry.getKey());
                    String subjIri = entry.getKey();

                    // 先直接检查该 subject IRI 是否已在 TDB2 中存在
                    String existCheckQuery = String.format(
                            "SELECT (COUNT(?p) AS ?c) WHERE { GRAPH <%s> { <%s> ?p ?o } }",
                            graphUri, subjIri);
                    try {
                        ReasoningService.SparqlQueryResult existCheck =
                                reasoningService.executeSparqlQuery(graphUri, existCheckQuery);
                        if (existCheck.isSuccess() && existCheck.getResults() != null && !existCheck.getResults().isEmpty()) {
                            String cnt = existCheck.getResults().get(0).get("c");
                            if (cnt != null && Integer.parseInt(cnt) > 0) {
                                log.info("知识更新: subject IRI 已存在 TDB2，直接复用: {}", subjIri);
                                continue; // 已存在，跳过属性查找
                            }
                        }
                    } catch (Exception e) {
                        log.warn("知识更新: 存在性检查失败 s={}: {}", subjIri, e.getMessage());
                    }

                    // 至少要有 2 个属性值才尝试属性匹配
                    if (idProps.size() < 2 || clsIri == null) continue;

                    // 动态构建 SPARQL：用所有提供的属性值查找已有实例
                    StringBuilder triplePatterns = new StringBuilder();
                    StringBuilder filterClauses = new StringBuilder();
                    int idx = 0;
                    for (Map.Entry<String, String> pv : idProps.entrySet()) {
                        String pIri = pv.getKey(), val = pv.getValue();
                        if (pIri == null || val == null) continue;
                        triplePatterns.append(String.format("?existing <%s> ?v%d . ", pIri, idx));
                        filterClauses.append(String.format("FILTER(CONTAINS(STR(?v%d), \"%s\")) ", idx,
                                val.replace("\\", "\\\\").replace("\"", "\\\"")));
                        idx++;
                    }
                    String lookupQuery = String.format(
                            "SELECT ?existing WHERE { GRAPH <%s> { ?existing a <%s> . %s %s } } LIMIT 1",
                            graphUri, clsIri, triplePatterns.toString(), filterClauses.toString());
                    try {
                        ReasoningService.SparqlQueryResult found =
                                reasoningService.executeSparqlQuery(graphUri, lookupQuery);
                        if (found.isSuccess() && found.getResults() != null && !found.getResults().isEmpty()) {
                            String existingIri = found.getResults().get(0).get("existing");
                            if (existingIri != null && !existingIri.equals(entry.getKey())) {
                                log.info("知识更新: 实体解析 {} -> {} (已有实例, 匹配属性数={})",
                                        entry.getKey(), existingIri, idProps.size());
                                for (Map<String, String> f : facts) {
                                    if (entry.getKey().equals(f.get("subjectIri"))) {
                                        f.put("subjectIri", existingIri);
                                    }
                                }
                            }
                        }
                    } catch (Exception e) {
                        log.warn("知识更新: 实体解析查询失败 s={}: {}", entry.getKey(), e.getMessage());
                    }
                }

                // Normalize predicate IRIs to TBox namespace before TTL conversion
                // LLM may use instance namespace (e.g. ontologybuilder.local/ontology/{id}#prop)
                // but the canonical definition is in the ontology namespace (e.g. ontologybuilder.local/ontology#prop)
                int normalizedCount = 0;
                for (Map<String, String> f : facts) {
                    String predicateIri = f.get("predicateIri");
                    if (predicateIri != null) {
                        String localName = predicateIri.contains("#")
                                ? predicateIri.substring(predicateIri.lastIndexOf('#') + 1)
                                : predicateIri.substring(predicateIri.lastIndexOf('/') + 1);
                        String tboxIri = tboxPropertyMap.get(localName);
                        if (tboxIri != null && !tboxIri.equals(predicateIri)) {
                            log.info("知识更新: 标准化属性IRI {} -> {}", predicateIri, tboxIri);
                            f.put("predicateIri", tboxIri);
                            normalizedCount++;
                        }
                    }
                }
                if (normalizedCount > 0) {
                    log.info("知识更新: 共标准化 {} 个属性IRI到TBox命名空间", normalizedCount);
                }

                // 自动覆盖旧值：对每个 fact，查询 TDB2 中同一 (subjectIri, predicateIri) 的已有三元组并删除
                // 无需依赖 LLM 提供 oldValue，系统内部自动处理
                StringBuilder deleteSb = new StringBuilder();
                int deleteCount = 0;
                for (Map<String, String> f : facts) {
                    String subj = f.get("subjectIri");
                    String pred = f.get("predicateIri");
                    if (subj == null || pred == null) continue;

                    // 查询已有的 (s, p, ?o) 三元组
                    String probeQuery = String.format(
                            "SELECT ?o WHERE { GRAPH <%s> { <%s> <%s> ?o } }",
                            graphUri, subj, pred);
                    try {
                        ReasoningService.SparqlQueryResult probeResult =
                                reasoningService.executeSparqlQuery(graphUri, probeQuery);
                        if (probeResult.isSuccess() && probeResult.getResults() != null) {
                            String objType = f.get("objectType");
                            for (Map<String, String> row : probeResult.getResults()) {
                                String oldVal = row.get("o");
                                if (oldVal == null) continue;
                                if ("iri".equals(objType)) {
                                    deleteSb.append("<").append(subj).append("> <").append(pred)
                                            .append("> <").append(oldVal).append("> .\n");
                                } else {
                                    String escaped = oldVal.replace("\\", "\\\\").replace("\"", "\\\"");
                                    deleteSb.append("<").append(subj).append("> <").append(pred)
                                            .append("> \"").append(escaped).append("\" .\n");
                                }
                                deleteCount++;
                            }
                        }
                    } catch (Exception e) {
                        log.warn("知识更新: 查询已有值失败 s={} p={}: {}", subj, pred, e.getMessage());
                    }
                }
                if (deleteCount > 0) {
                    String deleteTtl = deleteSb.toString();
                    log.info("知识更新: 覆盖删除 {} 条已有三元组\n{}", deleteCount, deleteTtl.trim());
                    int deleted = reasoningService.deleteTtlSnippet(deleteTtl, graphUri);
                    log.info("知识更新: 实际删除 {} 条已有三元组", deleted);
                }

                // Convert facts to TTL snippet
                // 收集需要声明 rdf:type 的 subject IRI（避免重复声明）
                Set<String> typedSubjects = new HashSet<>();
                StringBuilder sb = new StringBuilder();
                for (Map<String, String> f : facts) {
                    String subj = f.get("subjectIri");
                    String pred = f.get("predicateIri");
                    String objVal = f.get("objectValue");
                    String objType = f.get("objectType");
                    String clsIri = f.get("subjectClassIri");
                    if (subj == null || pred == null || objVal == null) continue;
                    // 为每个新 subject 追加 rdf:type 声明（确保 SPARQL 能查到该实例）
                    if (clsIri != null && !clsIri.isBlank() && typedSubjects.add(subj)) {
                        sb.append("<").append(subj).append("> <http://www.w3.org/1999/02/22-rdf-syntax-ns#type> <")
                                .append(clsIri).append("> .\n");
                    }
                    if ("iri".equals(objType)) {
                        sb.append("<").append(subj).append("> <").append(pred)
                                .append("> <").append(objVal).append("> .\n");
                    } else {
                        String escaped = objVal.replace("\\", "\\\\").replace("\"", "\\\"");
                        sb.append("<").append(subj).append("> <").append(pred)
                                .append("> \"").append(escaped).append("\" .\n");
                    }
                }
                ttlToApply = sb.toString();
            }

            int triples = reasoningService.loadTtlSnippet(ttlToApply, graphUri);
            log.info("知识更新完成: graphUri={}, 新增 {} 条三元组", graphUri, triples);
            return Result.success(Map.of(
                    "mode", "apply",
                    "validation", validationResult,
                    "applied", true,
                    "tripleCount", triples,
                    "graphUri", graphUri));
        } catch (Exception e) {
            log.error("知识更新写入失败: {}", e.getMessage(), e);
            return Result.error("知识更新写入失败: " + e.getMessage());
        }
    }

    @GetMapping("/{id}/probe-schema")
    @Operation(summary = "探测 TDB2 命名图中的实际类和属性 IRI",
               description = "当 SPARQL 查询返回 0 结果时，调用此接口探测 TDB2 命名图中实际存在的类（rdf:type）和谓词列表")
    public Result<Map<String, Object>> probeSchema(@PathVariable String id) {
        // 解析 TDB2 命名图：支持 instanceId、jenaModelName、ontologyId
        String effectiveModelName = null;

        // 0. 按 instanceId 查找
        Optional<OntologyInstance> instanceOpt = instanceRepository.findById(id);
        if (instanceOpt.isPresent()) {
            effectiveModelName = instanceOpt.get().getGraphUri();
            log.info("探测: 通过 instanceId 找到本体实例 {}，使用 graphUri: {}", id, effectiveModelName);
        }

        // 1. 按 jenaModelName 查找
        Optional<InstantiationTask> taskByJena = Optional.empty();
        if (effectiveModelName == null) {
            List<InstantiationTask> tasksByJena = instantiationTaskRepository.findByJenaModelName(id);
            taskByJena = tasksByJena.stream()
                    .filter(t -> t.getStatus() == InstantiationTask.TaskStatus.COMPLETED)
                    .findFirst();
            if (taskByJena.isPresent()) {
                effectiveModelName = taskByJena.get().getJenaModelName();
                if (effectiveModelName == null || effectiveModelName.isBlank()) {
                    effectiveModelName = "inst_" + taskByJena.get().getId();
                }
            }
        }

        // 2. 按 ontologyId 查找
        Optional<InstantiationTask> taskByOnto = Optional.empty();
        if (effectiveModelName == null) {
            List<InstantiationTask> tasks = instantiationTaskRepository.findByOntologyId(id);
            taskByOnto = tasks.stream()
                    .filter(t -> t.getStatus() == InstantiationTask.TaskStatus.COMPLETED)
                    .max(Comparator.comparing(InstantiationTask::getVersion,
                            Comparator.nullsLast(Comparator.reverseOrder())));
            if (taskByOnto.isPresent()) {
                effectiveModelName = taskByOnto.get().getJenaModelName();
                if (effectiveModelName == null || effectiveModelName.isBlank()) {
                    effectiveModelName = "inst_" + taskByOnto.get().getId();
                }
            }
        }

        // 3. 直接用作 graphUri
        if (effectiveModelName == null) {
            effectiveModelName = id;
        }

        log.info("探测 TDB2 Schema: ontologyId={}, effectiveModelName={}", id, effectiveModelName);

        final String modelName = effectiveModelName;

        // 如果命名图为空，尝试自动加载 TTL 到 TDB2（与 SPARQL 查询端点相同的 fallback 逻辑）
        if (!reasoningService.isModelLoaded(modelName)) {
            // 解析真正的本体 ID：id 可能是 instanceId，需从实例或任务中提取 ontologyId
            String ontologyId = id;
            if (instanceOpt.isPresent() && instanceOpt.get().getOntologyId() != null
                    && !instanceOpt.get().getOntologyId().isBlank()) {
                ontologyId = instanceOpt.get().getOntologyId();
            } else if (taskByJena.isPresent() && taskByJena.get().getOntologyId() != null
                    && !taskByJena.get().getOntologyId().isBlank()) {
                ontologyId = taskByJena.get().getOntologyId();
            } else if (taskByOnto.isPresent() && taskByOnto.get().getOntologyId() != null
                    && !taskByOnto.get().getOntologyId().isBlank()) {
                ontologyId = taskByOnto.get().getOntologyId();
            }
            log.warn("探测: 本体 {} 在命名图 {} 中未找到，尝试自动加载 TTL 到 TDB2", ontologyId, modelName);
            try {
                Ontology ont = ontologyService.findById(ontologyId);
                if (ont.getTtlPath() != null && Files.exists(Paths.get(ont.getTtlPath()))) {
                    String ttl = Files.readString(Paths.get(ont.getTtlPath()), StandardCharsets.UTF_8);
                    reasoningService.loadTtlContent(ttl, modelName);
                    log.info("探测: 本体自动加载到TDB2成功: ontologyId={}, modelName={}", ontologyId, modelName);
                }
            } catch (NotFoundException e) {
                log.warn("探测: 本体自动加载TDB2失败，本体未找到: ontologyId={}", ontologyId);
            } catch (Exception autoEx) {
                log.warn("探测: 本体自动加载TDB2失败: ontologyId={}, error={}", ontologyId, autoEx.getMessage());
            }
        }

        // ★ 优化：5个基础 SPARQL 探测查询并行执行（含 domain）
        String probeClasses = "SELECT DISTINCT ?class WHERE { ?s a ?class } ORDER BY ?class";
        String probePredicates = "SELECT DISTINCT ?predicate WHERE { ?s ?predicate ?o } ORDER BY ?predicate";
        String probeObjProps = "PREFIX owl: <http://www.w3.org/2002/07/owl#> SELECT DISTINCT ?prop WHERE { ?prop a owl:ObjectProperty }";
        String probeDataProps = "PREFIX owl: <http://www.w3.org/2002/07/owl#> SELECT DISTINCT ?prop WHERE { ?prop a owl:DatatypeProperty }";
        String probeDomain = "SELECT DISTINCT ?prop ?domain WHERE { ?prop rdfs:domain ?domain }";

        java.util.concurrent.CompletableFuture<ReasoningService.SparqlQueryResult> classFuture =
            java.util.concurrent.CompletableFuture.supplyAsync(
                () -> reasoningService.executeSparqlQuery(modelName, probeClasses), sseExecutor);
        java.util.concurrent.CompletableFuture<ReasoningService.SparqlQueryResult> predFuture =
            java.util.concurrent.CompletableFuture.supplyAsync(
                () -> reasoningService.executeSparqlQuery(modelName, probePredicates), sseExecutor);
        java.util.concurrent.CompletableFuture<ReasoningService.SparqlQueryResult> objPropFuture =
            java.util.concurrent.CompletableFuture.supplyAsync(
                () -> reasoningService.executeSparqlQuery(modelName, probeObjProps), sseExecutor);
        java.util.concurrent.CompletableFuture<ReasoningService.SparqlQueryResult> dataPropFuture =
            java.util.concurrent.CompletableFuture.supplyAsync(
                () -> reasoningService.executeSparqlQuery(modelName, probeDataProps), sseExecutor);
        java.util.concurrent.CompletableFuture<ReasoningService.SparqlQueryResult> domainFuture =
            java.util.concurrent.CompletableFuture.supplyAsync(
                () -> reasoningService.executeSparqlQuery(modelName, probeDomain), sseExecutor);

        java.util.concurrent.CompletableFuture.allOf(classFuture, predFuture, objPropFuture, dataPropFuture, domainFuture).join();

        java.util.List<String> classes = new java.util.ArrayList<>();
        java.util.List<String> predicates = new java.util.ArrayList<>();
        java.util.List<String> objPropsWithSchema = new java.util.ArrayList<>();
        java.util.List<String> dataPropsWithSchema = new java.util.ArrayList<>();

        try {
            ReasoningService.SparqlQueryResult classResult = classFuture.get();
            if (classResult.isSuccess() && classResult.getResults() != null) {
                for (Map<String, String> binding : classResult.getResults()) {
                    String cls = binding.get("class");
                    if (cls != null) classes.add(cls);
                }
            }
        } catch (Exception e) {
            log.warn("探测classes失败: {}", e.getMessage());
        }
        try {
            ReasoningService.SparqlQueryResult predResult = predFuture.get();
            if (predResult.isSuccess() && predResult.getResults() != null) {
                for (Map<String, String> binding : predResult.getResults()) {
                    String pred = binding.get("predicate");
                    if (pred != null) predicates.add(pred);
                }
            }
        } catch (Exception e) {
            log.warn("探测predicates失败: {}", e.getMessage());
        }

        // 过滤掉标准 RDF/OWL 命名空间的谓词，只保留业务属性
        java.util.List<String> businessPredicates = predicates.stream()
                .filter(p -> p != null && !p.isBlank() && !p.startsWith("http://www.w3.org/"))
                .toList();

        java.util.Set<String> schemaPredicates = new java.util.LinkedHashSet<>(businessPredicates);
        try {
            ReasoningService.SparqlQueryResult objPropResult = objPropFuture.get();
            if (objPropResult.isSuccess() && objPropResult.getResults() != null) {
                for (Map<String, String> binding : objPropResult.getResults()) {
                    String prop = binding.get("prop");
                    if (prop != null && !prop.isBlank() && !prop.startsWith("http://www.w3.org/")) {
                        schemaPredicates.add(prop);
                        objPropsWithSchema.add(prop);
                    }
                }
            }
        } catch (Exception e) {
            log.warn("探测对象属性失败: {}", e.getMessage());
        }
        try {
            ReasoningService.SparqlQueryResult dataPropResult = dataPropFuture.get();
            if (dataPropResult.isSuccess() && dataPropResult.getResults() != null) {
                for (Map<String, String> binding : dataPropResult.getResults()) {
                    String prop = binding.get("prop");
                    if (prop != null && !prop.isBlank() && !prop.startsWith("http://www.w3.org/")) {
                        schemaPredicates.add(prop);
                        dataPropsWithSchema.add(prop);
                    }
                }
            }
        } catch (Exception e) {
            log.warn("探测数据属性失败: {}", e.getMessage());
        }
        java.util.List<String> allBusinessPredicates = new java.util.ArrayList<>(schemaPredicates);

        // 区分有实例数据的属性 vs 只有Schema定义的属性（对象属性 + 数据属性）
        java.util.Set<String> predicatesWithData = new java.util.HashSet<>(businessPredicates);
        java.util.List<String> objPropsWithData = new java.util.ArrayList<>();
        java.util.List<String> objPropsWithoutData = new java.util.ArrayList<>();
        for (String prop : objPropsWithSchema) {
            if (predicatesWithData.contains(prop)) {
                objPropsWithData.add(prop);
            } else {
                objPropsWithoutData.add(prop);
            }
        }

        // ★ 数据属性同样区分有实例数据 vs 只有Schema定义（SWRL规则中声明但无实际三元组）
        java.util.List<String> dataPropsWithData = new java.util.ArrayList<>();
        java.util.List<String> dataPropsWithoutData = new java.util.ArrayList<>();
        for (String prop : dataPropsWithSchema) {
            if (predicatesWithData.contains(prop)) {
                dataPropsWithData.add(prop);
            } else {
                dataPropsWithoutData.add(prop);
            }
        }

        // ★ 优化：数据属性采样查询并行执行（仅对实际有数据的数据属性采样）
        java.util.Map<String, java.util.List<String>> dataPropertySamples = new java.util.concurrent.ConcurrentHashMap<>();
        if (!dataPropsWithData.isEmpty()) {
            java.util.List<java.util.concurrent.CompletableFuture<Void>> sampleFutures = new java.util.ArrayList<>();
            for (String propIri : dataPropsWithData) {
                final String iri = propIri;
                sampleFutures.add(java.util.concurrent.CompletableFuture.runAsync(() -> {
                    try {
                        String sampleQuery = String.format(
                            "SELECT DISTINCT ?val WHERE { ?s <%s> ?val } LIMIT 5", iri);
                        ReasoningService.SparqlQueryResult sampleResult =
                            reasoningService.executeSparqlQuery(modelName, sampleQuery);
                        if (sampleResult.isSuccess() && sampleResult.getResults() != null) {
                            java.util.List<String> values = new java.util.ArrayList<>();
                            for (Map<String, String> binding : sampleResult.getResults()) {
                                String val = binding.get("val");
                                if (val != null && !val.isBlank()) {
                                    values.add(val);
                                }
                            }
                            if (!values.isEmpty()) {
                                dataPropertySamples.put(iri, values);
                            }
                        }
                    } catch (Exception e) {
                        log.debug("采样数据属性值失败: propIri={}, error={}", iri, e.getMessage());
                    }
                }, sseExecutor));
            }
            java.util.concurrent.CompletableFuture.allOf(sampleFutures.toArray(new java.util.concurrent.CompletableFuture[0])).join();
        }

        // 处理 rdfs:domain 查询结果
        Map<String, String> predicateDomains = new java.util.LinkedHashMap<>();
        try {
            ReasoningService.SparqlQueryResult domainResult = domainFuture.get();
            if (domainResult.isSuccess() && domainResult.getResults() != null) {
                for (Map<String, String> binding : domainResult.getResults()) {
                    String propIri = binding.get("prop");
                    String domainIri = binding.get("domain");
                    if (propIri != null && domainIri != null) {
                        predicateDomains.putIfAbsent(propIri, domainIri);
                    }
                }
            }
        } catch (Exception e) {
            log.warn("探测 rdfs:domain 失败: {}", e.getMessage());
        }

        // 标准化 IRI 到 TBox 命名空间: 从 OWL 本体构建 class/property localName -> TBox IRI 映射
        Map<String, String> classMap = new HashMap<>();
        Map<String, String> propMap = new HashMap<>();
        try {
            String ontId = id;
            if (instanceOpt.isPresent()) ontId = instanceOpt.get().getOntologyId();
            Ontology ont = ontologyService.findById(ontId);
            if (ont != null && ont.getTtlPath() != null && Files.exists(Paths.get(ont.getTtlPath()))) {
                String ttl = Files.readString(Paths.get(ont.getTtlPath()), StandardCharsets.UTF_8);
                org.semanticweb.owlapi.model.OWLOntologyManager mgr =
                        org.semanticweb.owlapi.apibinding.OWLManager.createOWLOntologyManager();
                org.semanticweb.owlapi.model.OWLOntology owlOnt = mgr.loadOntologyFromOntologyDocument(
                        new java.io.ByteArrayInputStream(ttl.getBytes(StandardCharsets.UTF_8)));
                for (org.semanticweb.owlapi.model.OWLClass c : owlOnt.getClassesInSignature()) {
                    String iri = c.getIRI().toString();
                    String ln = iri.contains("#") ? iri.substring(iri.lastIndexOf('#') + 1) : iri.substring(iri.lastIndexOf('/') + 1);
                    classMap.putIfAbsent(ln, iri);
                }
                for (org.semanticweb.owlapi.model.OWLDataProperty dp : owlOnt.getDataPropertiesInSignature()) {
                    String iri = dp.getIRI().toString();
                    String ln = iri.contains("#") ? iri.substring(iri.lastIndexOf('#') + 1) : iri.substring(iri.lastIndexOf('/') + 1);
                    propMap.putIfAbsent(ln, iri);
                }
                for (org.semanticweb.owlapi.model.OWLObjectProperty op : owlOnt.getObjectPropertiesInSignature()) {
                    String iri = op.getIRI().toString();
                    String ln = iri.contains("#") ? iri.substring(iri.lastIndexOf('#') + 1) : iri.substring(iri.lastIndexOf('/') + 1);
                    propMap.putIfAbsent(ln, iri);
                }
            }
        } catch (Exception e) {
            log.warn("probeSchema: 加载TBox进行IRI标准化失败: {}", e.getMessage());
        }
        // 标准化函数
        java.util.function.Function<String, String> normalizeIri = iri -> {
            if (iri == null) return null;
            String ln = iri.contains("#") ? iri.substring(iri.lastIndexOf('#') + 1) : iri.substring(iri.lastIndexOf('/') + 1);
            String mapped = propMap.get(ln);
            if (mapped == null) mapped = classMap.get(ln);
            return (mapped != null && !mapped.equals(iri)) ? mapped : iri;
        };
        // 标准化各列表
        classes = classes.stream().map(normalizeIri).distinct().collect(Collectors.toList());
        predicates = predicates.stream().map(normalizeIri).distinct().collect(Collectors.toList());
        allBusinessPredicates = allBusinessPredicates.stream().map(normalizeIri).distinct().collect(Collectors.toList());
        // 标准化 domain 映射的 key 和 value
        Map<String, String> normalizedDomains = new LinkedHashMap<>();
        for (Map.Entry<String, String> e : predicateDomains.entrySet()) {
            normalizedDomains.put(normalizeIri.apply(e.getKey()), normalizeIri.apply(e.getValue()));
        }
        predicateDomains = normalizedDomains;
        // 标准化其他 IRI 列表
        objPropsWithData = objPropsWithData.stream().map(normalizeIri).distinct().collect(Collectors.toList());
        objPropsWithoutData = objPropsWithoutData.stream().map(normalizeIri).distinct().collect(Collectors.toList());
        dataPropsWithData = dataPropsWithData.stream().map(normalizeIri).distinct().collect(Collectors.toList());
        dataPropsWithoutData = dataPropsWithoutData.stream().map(normalizeIri).distinct().collect(Collectors.toList());
        // 标准化采样 key
        Map<String, java.util.List<String>> normalizedSamples = new LinkedHashMap<>();
        for (Map.Entry<String, java.util.List<String>> e : dataPropertySamples.entrySet()) {
            normalizedSamples.put(normalizeIri.apply(e.getKey()), e.getValue());
        }
        dataPropertySamples.clear();
        dataPropertySamples.putAll(normalizedSamples);
        log.info("probeSchema IRI标准化: classMap={}, propMap={}", classMap.size(), propMap.size());

        Map<String, Object> probeResult = new java.util.LinkedHashMap<>();
        probeResult.put("model", modelName);
        probeResult.put("totalClasses", classes.size());
        probeResult.put("totalPredicates", predicates.size());
        probeResult.put("businessPredicates", allBusinessPredicates);
        probeResult.put("classes", classes);
        probeResult.put("predicates", predicates);
        probeResult.put("predicateDomains", predicateDomains);
        probeResult.put("objectPropertiesWithData", objPropsWithData);
        probeResult.put("objectPropertiesWithoutData", objPropsWithoutData);
        probeResult.put("dataPropertiesWithData", dataPropsWithData);
        probeResult.put("dataPropertiesWithoutData", dataPropsWithoutData);
        probeResult.put("dataPropertySamples", dataPropertySamples);

        log.info("探测结果: model={}, {} 个类, {} 个业务谓词(含Schema声明), {} 个domain映射, 对象属性: {}有数据/{}无数据, 数据属性: {}有数据/{}无数据, 数据属性采样: {}个",
                modelName, classes.size(), allBusinessPredicates.size(), predicateDomains.size(),
                objPropsWithData.size(), objPropsWithoutData.size(),
                dataPropsWithData.size(), dataPropsWithoutData.size(),
                dataPropertySamples.size());
        return Result.success(probeResult);
    }

    @GetMapping("/{id}/object-property-graph")
    @Operation(summary = "获取本体对象属性拓扑图",
               description = "返回类节点、对象属性边、标识属性共享组，供 KBQA 自动展开查询范围，避免遗漏关联类")
    public Result<Map<String, Object>> getObjectPropertyGraph(@PathVariable String id) {
        // 复用 probeSchema 的模型名解析逻辑
        String effectiveModelName = null;

        Optional<OntologyInstance> instanceOpt = instanceRepository.findById(id);
        if (instanceOpt.isPresent()) {
            effectiveModelName = instanceOpt.get().getGraphUri();
        }

        if (effectiveModelName == null) {
            List<InstantiationTask> tasksByJena = instantiationTaskRepository.findByJenaModelName(id);
            Optional<InstantiationTask> taskByJena = tasksByJena.stream()
                    .filter(t -> t.getStatus() == InstantiationTask.TaskStatus.COMPLETED)
                    .findFirst();
            if (taskByJena.isPresent()) {
                effectiveModelName = taskByJena.get().getJenaModelName();
                if (effectiveModelName == null || effectiveModelName.isBlank()) {
                    effectiveModelName = "inst_" + taskByJena.get().getId();
                }
            }
        }

        if (effectiveModelName == null) {
            List<InstantiationTask> tasks = instantiationTaskRepository.findByOntologyId(id);
            Optional<InstantiationTask> taskByOnto = tasks.stream()
                    .filter(t -> t.getStatus() == InstantiationTask.TaskStatus.COMPLETED)
                    .max(Comparator.comparing(InstantiationTask::getVersion,
                            Comparator.nullsLast(Comparator.reverseOrder())));
            if (taskByOnto.isPresent()) {
                effectiveModelName = taskByOnto.get().getJenaModelName();
                if (effectiveModelName == null || effectiveModelName.isBlank()) {
                    effectiveModelName = "inst_" + taskByOnto.get().getId();
                }
            }
        }

        if (effectiveModelName == null) {
            effectiveModelName = id;
        }

        log.info("Schema 拓扑图探测: id={}, effectiveModelName={}", id, effectiveModelName);
        final String modelName = effectiveModelName;

        // 自动加载 TTL（如果未加载）
        if (!reasoningService.isModelLoaded(modelName)) {
            String ontologyId = id;
            if (instanceOpt.isPresent() && instanceOpt.get().getOntologyId() != null
                    && !instanceOpt.get().getOntologyId().isBlank()) {
                ontologyId = instanceOpt.get().getOntologyId();
            }
            log.warn("Schema拓扑图: 本体 {} 未加载，尝试自动加载", ontologyId);
            try {
                Ontology ont = ontologyService.findById(ontologyId);
                if (ont.getTtlPath() != null && Files.exists(Paths.get(ont.getTtlPath()))) {
                    String ttl = Files.readString(Paths.get(ont.getTtlPath()), StandardCharsets.UTF_8);
                    reasoningService.loadTtlContent(ttl, modelName);
                }
            } catch (Exception autoEx) {
                log.warn("Schema拓扑图: 自动加载失败: {}", autoEx.getMessage());
            }
        }

        // Step 1: 并行探测基础数据（类、属性、domain）
        String probeClasses = "SELECT DISTINCT ?class WHERE { ?s a ?class } ORDER BY ?class";
        String probePredicates = "SELECT DISTINCT ?predicate WHERE { ?s ?predicate ?o } ORDER BY ?predicate";
        String probeObjProps = "PREFIX owl: <http://www.w3.org/2002/07/owl#> SELECT DISTINCT ?prop WHERE { ?prop a owl:ObjectProperty }";
        String probeDataProps = "PREFIX owl: <http://www.w3.org/2002/07/owl#> SELECT DISTINCT ?prop WHERE { ?prop a owl:DatatypeProperty }";
        String probeDomain = "SELECT DISTINCT ?prop ?domain WHERE { ?prop rdfs:domain ?domain }";

        CompletableFuture<ReasoningService.SparqlQueryResult> classFuture =
            CompletableFuture.supplyAsync(
                () -> reasoningService.executeSparqlQuery(modelName, probeClasses), sseExecutor);
        CompletableFuture<ReasoningService.SparqlQueryResult> predFuture =
            CompletableFuture.supplyAsync(
                () -> reasoningService.executeSparqlQuery(modelName, probePredicates), sseExecutor);
        CompletableFuture<ReasoningService.SparqlQueryResult> objPropFuture =
            CompletableFuture.supplyAsync(
                () -> reasoningService.executeSparqlQuery(modelName, probeObjProps), sseExecutor);
        CompletableFuture<ReasoningService.SparqlQueryResult> dataPropFuture =
            CompletableFuture.supplyAsync(
                () -> reasoningService.executeSparqlQuery(modelName, probeDataProps), sseExecutor);
        CompletableFuture<ReasoningService.SparqlQueryResult> domainFuture =
            CompletableFuture.supplyAsync(
                () -> reasoningService.executeSparqlQuery(modelName, probeDomain), sseExecutor);

        CompletableFuture.allOf(classFuture, predFuture, objPropFuture, dataPropFuture, domainFuture).join();

        List<String> classes = new ArrayList<>();
        List<String> predicates = new ArrayList<>();
        List<String> objPropsWithSchema = new ArrayList<>();
        List<String> dataPropsWithSchema = new ArrayList<>();
        Map<String, String> predicateDomains = new LinkedHashMap<>();

        try { classFuture.get().getResults().forEach(r -> { String c = r.get("class"); if (c != null) classes.add(c); }); } catch (Exception e) { log.warn("拓扑图探测classes失败: {}", e.getMessage()); }
        try { predFuture.get().getResults().forEach(r -> { String p = r.get("predicate"); if (p != null) predicates.add(p); }); } catch (Exception e) { log.warn("拓扑图探测predicates失败: {}", e.getMessage()); }
        try { objPropFuture.get().getResults().forEach(r -> { String p = r.get("prop"); if (p != null && !p.startsWith("http://www.w3.org/")) objPropsWithSchema.add(p); }); } catch (Exception e) { log.warn("拓扑图探测objProps失败: {}", e.getMessage()); }
        try { dataPropFuture.get().getResults().forEach(r -> { String p = r.get("prop"); if (p != null && !p.startsWith("http://www.w3.org/")) dataPropsWithSchema.add(p); }); } catch (Exception e) { log.warn("拓扑图探测dataProps失败: {}", e.getMessage()); }
        try { domainFuture.get().getResults().forEach(r -> { if (r.get("prop") != null && r.get("domain") != null) predicateDomains.putIfAbsent(r.get("prop"), r.get("domain")); }); } catch (Exception e) { log.warn("拓扑图探测domain失败: {}", e.getMessage()); }

        List<String> businessPredicates = predicates.stream()
                .filter(p -> p != null && !p.isBlank() && !p.startsWith("http://www.w3.org/"))
                .toList();

        // 区分有/无实例数据
        Set<String> predsWithData = new HashSet<>(businessPredicates);
        List<String> objPropsWithData = objPropsWithSchema.stream()
                .filter(predsWithData::contains).toList();

        // Step 2: 对象属性实际连接探测（核心新增）
        List<Map<String, Object>> edges = reasoningService.probeObjectPropertyConnections(modelName, objPropsWithData);

        // Step 3: 标识属性共享探测（核心新增）
        List<Map<String, Object>> identityOverlaps = reasoningService.probeIdentityOverlaps(modelName, businessPredicates);

        // Step 4: 类节点信息（标识属性、数据属性、实例计数）
        List<Map<String, Object>> nodes = new ArrayList<>();
        for (String clsIri : classes) {
            if (clsIri.startsWith("http://www.w3.org/")) continue;
            Map<String, Object> node = new LinkedHashMap<>();
            node.put("iri", clsIri);
            node.put("localName", clsIri.contains("#")
                    ? clsIri.substring(clsIri.lastIndexOf('#') + 1)
                    : clsIri.substring(clsIri.lastIndexOf('/') + 1));

            // 该类的数据属性
            List<String> dataProps = new ArrayList<>();
            for (String dp : dataPropsWithSchema) {
                String domain = predicateDomains.get(dp);
                if (domain != null && domain.equals(clsIri)) dataProps.add(dp);
            }
            node.put("dataProps", dataProps);

            // 标识属性（该类中属于标识属性的数据属性）
            List<String> idProps = new ArrayList<>();
            for (String dp : dataProps) {
                String local = dp.contains("#") ? dp.substring(dp.lastIndexOf('#') + 1) : dp.substring(dp.lastIndexOf('/') + 1);
                String lower = local.toLowerCase();
                if (lower.contains("name") || lower.contains("code") || lower.contains("id")
                        || lower.contains("名称") || lower.contains("编号") || lower.contains("代码")
                        || lower.contains("label") || lower.contains("title")) {
                    idProps.add(dp);
                }
            }
            node.put("identityProps", idProps);
            nodes.add(node);
        }

        // 组装结果
        Map<String, Object> graphResult = new LinkedHashMap<>();
        graphResult.put("model", modelName);
        graphResult.put("nodes", nodes);
        graphResult.put("edges", edges);
        graphResult.put("identityOverlaps", identityOverlaps);

        log.info("Schema 拓扑图: {} 个节点, {} 条边, {} 个标识属性共享组",
                nodes.size(), edges.size(), identityOverlaps.size());
        return Result.success(graphResult);
    }

    // ===== 版本管理 =====

    /** 解析 domain 表达式，提取单个类 IRI 列表。
     *  支持 ":ClassName"、逗号分隔、"[ owl:unionOf (:A :B ...) ]" 三种格式。 */
    private java.util.List<String> extractIrisFromDomainExpr(Object domainRaw) {
        java.util.List<String> iris = new java.util.ArrayList<>();
        if (domainRaw == null) return iris;
        String domain = safeString(domainRaw).trim();
        if (domain.isEmpty()) return iris;

        // 逗号分隔格式：:A,:B,:C 或 :A, :B, :C
        if (domain.contains(",")) {
            for (String part : domain.split(",")) {
                String iri = part.trim();
                if (iri.startsWith(":") && !iri.isBlank()) {
                    iris.add(iri);
                }
            }
            return iris;
        }

        // 单类 IRI：:ClassName
        if (domain.startsWith(":") && !domain.contains(" ")) {
            iris.add(domain);
            return iris;
        }

        // owl:unionOf 表达式：[ owl:unionOf (:A :B ...) ]
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("(:[\\w_\\-]+)").matcher(domain);
        while (m.find()) {
            iris.add(m.group(1));
        }
        return iris;
    }

    /**
     * 将数据属性按 domain 匹配到 ObjectType 的 propertiesJson。
     * 三层 fallback 策略：精确匹配 → 命名空间匹配 → 首个类兜底，确保属性不丢失。
     */
    private void attachDataPropsToClasses(
            java.util.Map<String, java.util.List<java.util.Map<String, String>>> propsByDomain,
            java.util.List<ObjectType> objectTypes) {
        if (propsByDomain.isEmpty() || objectTypes.isEmpty()) return;

        java.util.Set<String> matchedKeys = new java.util.HashSet<>();
        // 精确匹配：domain 与 ObjectType.iri 一致
        for (ObjectType objType : objectTypes) {
            java.util.List<java.util.Map<String, String>> attachedProps = propsByDomain.get(objType.getIri());
            if (attachedProps != null && !attachedProps.isEmpty()) {
                try {
                    objType.setPropertiesJson(objectMapper.writeValueAsString(attachedProps));
                    matchedKeys.add(objType.getIri());
                } catch (Exception e) {
                    log.error("序列化 propertiesJson 失败: iri={}", objType.getIri(), e);
                }
            }
        }

        // 收集未匹配的 domain
        java.util.List<String> unmatchedKeys = new java.util.ArrayList<>();
        for (java.util.Map.Entry<String, java.util.List<java.util.Map<String, String>>> entry : propsByDomain.entrySet()) {
            if (!matchedKeys.contains(entry.getKey()) && !entry.getValue().isEmpty()) {
                unmatchedKeys.add(entry.getKey());
            }
        }

        if (!unmatchedKeys.isEmpty()) {
            // 构建 namespace → ObjectType 映射
            java.util.Map<String, ObjectType> nsToClass = new java.util.LinkedHashMap<>();
            for (ObjectType objType : objectTypes) {
                if (objType.getIri() != null) {
                    String ns = extractNamespace(objType.getIri());
                    if (ns != null && !ns.isEmpty()) {
                        nsToClass.putIfAbsent(ns, objType);
                    }
                }
            }

            for (String unmatchedKey : unmatchedKeys) {
                java.util.List<java.util.Map<String, String>> orphanProps = propsByDomain.get(unmatchedKey);
                ObjectType targetClass = null;

                // 策略1: 通过 namespace 匹配
                if (unmatchedKey != null && !unmatchedKey.isEmpty()) {
                    String propNs = extractNamespace(unmatchedKey);
                    if (propNs != null) {
                        targetClass = nsToClass.get(propNs);
                        if (targetClass != null) {
                            log.info("DatatypeProperty domain '{}' 通过命名空间匹配到类 '{}'",
                                    unmatchedKey, targetClass.getIri());
                        }
                    }
                }

                // 策略2: 通过属性 IRI 命名空间匹配
                if (targetClass == null && !orphanProps.isEmpty()) {
                    String propIri = orphanProps.get(0).get("iri");
                    if (propIri != null) {
                        String propNs = extractNamespace(propIri);
                        if (propNs != null) {
                            targetClass = nsToClass.get(propNs);
                            if (targetClass != null) {
                                log.info("DatatypeProperty '{}' 通过属性IRI命名空间匹配到类 '{}'",
                                        propIri, targetClass.getIri());
                            }
                        }
                    }
                }

                // 策略3: fallback — 附加到第一个类
                if (targetClass == null) {
                    targetClass = objectTypes.get(0);
                    log.warn("DatatypeProperty domain '{}' 无法匹配任何已知类，回退到第一个类 '{}'，共 {} 条属性",
                            unmatchedKey, targetClass.getIri(), orphanProps.size());
                }

                // 合并到目标类的 propertiesJson
                try {
                    java.util.List<java.util.Map<String, String>> existingProps = null;
                    String existingJson = targetClass.getPropertiesJson();
                    if (existingJson != null && !existingJson.isBlank()) {
                        existingProps = objectMapper.readValue(existingJson, java.util.List.class);
                    }
                    java.util.List<java.util.Map<String, String>> merged = new java.util.ArrayList<>();
                    if (existingProps != null) {
                        merged.addAll(existingProps);
                    }
                    merged.addAll(orphanProps);
                    targetClass.setPropertiesJson(objectMapper.writeValueAsString(merged));
                    matchedKeys.add(unmatchedKey);
                } catch (Exception e) {
                    log.error("合并未匹配的 DatatypeProperty 失败: domain={}", unmatchedKey, e);
                }
            }
        }
        log.info("已将 DatatypeProperty 关联到对应类: {} 个domain分组, {} 个已匹配",
                propsByDomain.size(), matchedKeys.size());
    }

    private int countPattern(String text, String regex) {
        if (text == null) return 0;
        java.util.regex.Matcher m = java.util.regex.Pattern.compile(regex).matcher(text);
        int count = 0;
        while (m.find()) count++;
        return count;
    }

    /** 从 TTL 文本中提取指定类型的唯一 IRI 集合（如 owl:ObjectProperty / owl:DatatypeProperty） */
    private java.util.Set<String> extractUniqueIris(String ttlContent, String typeKeyword) {
        java.util.Set<String> iris = new java.util.LinkedHashSet<>();
        if (ttlContent == null) return iris;
        java.util.regex.Pattern p = java.util.regex.Pattern.compile(
            "(:[\\w_]+)\\s+a\\s+" + typeKeyword + "\\b");
        java.util.regex.Matcher m = p.matcher(ttlContent);
        while (m.find()) {
            iris.add(m.group(1));
        }
        return iris;
    }

    /** 从 JSON 属性列表中提取唯一 IRI 集合 */
    private java.util.Set<String> extractUniqueIrisFromJsonProps(List<Map<String, String>> props) {
        java.util.Set<String> iris = new java.util.LinkedHashSet<>();
        if (props == null) return iris;
        for (Map<String, String> prop : props) {
            Object iriRaw = ((java.util.Map<?, ?>) prop).get("iri");
            if (iriRaw != null) {
                String iri = iriRaw.toString().trim();
                if (!iri.isBlank()) iris.add(iri);
            }
        }
        return iris;
    }

    private int countIndividualsFromTtl(String ttlPath) {
        if (ttlPath == null) return 0;
        try {
            Path path = Paths.get(ttlPath);
            if (!Files.exists(path)) return 0;
            String content = Files.readString(path, StandardCharsets.UTF_8);
            java.util.regex.Matcher m = java.util.regex.Pattern.compile("\\ba\\s+owl:NamedIndividual\\b").matcher(content);
            int count = 0;
            while (m.find()) count++;
            return count;
        } catch (Exception e) {
            return 0;
        }
    }

    private int countDataPropertiesFromTtl(String ttlPath) {
        if (ttlPath == null) return 0;
        try {
            Path path = Paths.get(ttlPath);
            if (!Files.exists(path)) return 0;
            String content = Files.readString(path, StandardCharsets.UTF_8);
            // 统计 owl:DatatypeProperty 声明中 domain 包含的类数（展开 owl:unionOf）
            java.util.regex.Pattern dpPattern = java.util.regex.Pattern.compile(
                "(:[\\w_\\-]+)\\s+a\\s+owl:DatatypeProperty\\b[^.]*?\\.",
                java.util.regex.Pattern.DOTALL);
            java.util.regex.Matcher m = dpPattern.matcher(content);
            int count = 0;
            while (m.find()) {
                String decl = m.group();
                // 如果是 unionOf，统计括号内的类数；否则算 1
                java.util.regex.Matcher union = java.util.regex.Pattern.compile(
                    "owl:unionOf\\s*\\(([^)]+)\\)").matcher(decl);
                if (union.find()) {
                    String members = union.group(1);
                    java.util.regex.Matcher iriMatcher = java.util.regex.Pattern.compile("(:[\\w_\\-]+)").matcher(members);
                    while (iriMatcher.find()) count++;
                } else {
                    count++;
                }
            }
            return count;
        } catch (Exception e) {
            return 0;
        }
    }

    private int countObjectPropertiesFromTtl(String ttlPath) {
        if (ttlPath == null) return 0;
        try {
            Path path = Paths.get(ttlPath);
            if (!Files.exists(path)) return 0;
            String content = Files.readString(path, StandardCharsets.UTF_8);
            java.util.regex.Matcher m = java.util.regex.Pattern.compile("\\ba\\s+owl:ObjectProperty\\b").matcher(content);
            int count = 0;
            while (m.find()) count++;
            return count;
        } catch (Exception e) {
            return 0;
        }
    }

    @GetMapping("/versions")
    @Operation(summary = "获取所有本体列表（用于版本管理）")
    public Result<List<Map<String, Object>>> getAllOntologyVersions(
            @RequestParam(value = "name", required = false) String ontologyName) {
        List<Ontology> all = ontologyService.findAll();
        if (ontologyName != null && !ontologyName.isBlank()) {
            String searchLower = ontologyName.toLowerCase().trim();
            all = all.stream()
                .filter(o -> o.getName() != null && o.getName().toLowerCase().contains(searchLower))
                .toList();
        }
        List<Map<String, Object>> versions = new ArrayList<>();
        Map<String, Ontology> latestMap = new HashMap<>();
        for (Ontology o : all) {
            Ontology existing = latestMap.get(o.getName());
            if (existing == null || compareVersions(o.getVersion(), existing.getVersion()) > 0) {
                latestMap.put(o.getName(), o);
            }
        }
        for (Ontology o : all) {
            Map<String, Object> v = new HashMap<>();
            v.put("id", o.getId());
            v.put("version", o.getVersion());
            v.put("versionName", o.getVersionName());
            v.put("name", o.getName());
            v.put("description", o.getDescription());
            v.put("status", o.getStatus());
            v.put("classCount", o.getClassCount());
            v.put("propertyCount", o.getPropertyCount());
            v.put("ttlPath", o.getTtlPath());
            v.put("instantiated", o.getInstantiated());
            v.put("createdAt", o.getCreatedAt());
            v.put("updatedAt", o.getUpdatedAt());
            Ontology latest = latestMap.get(o.getName());
            v.put("current", latest != null && latest.getId().equals(o.getId()));
            // 补充统计信息
            v.put("ruleCount", ruleService.findByOntologyId(o.getId()).size());
            v.put("individualCount", countIndividualsFromTtl(o.getTtlPath()));
            v.put("objectPropertyCount", countObjectPropertiesFromTtl(o.getTtlPath()));
            v.put("dataPropertyCount", countDataPropertiesFromTtl(o.getTtlPath()));
            v.put("sourceMdFileName", o.getSourceMdFileName());
            v.put("sourceMdFileId", o.getSourceMdFileId());
            v.put("sourceModelName", o.getSourceModelName());
            v.put("ttlGeneratedAt", o.getTtlGeneratedAt());
            versions.add(v);
        }
        versions.sort((a, b) -> {
            String va = (String) a.get("version");
            String vb = (String) b.get("version");
            return vb != null ? compareVersions(vb, va) : -1;
        });
        return Result.success(versions);
    }

    @GetMapping("/{id}/versions")
    @Operation(summary = "获取指定本体的版本列表")
    public Result<List<Map<String, Object>>> getVersions(@PathVariable String id) {
        Ontology current = ontologyService.findById(id);
        String ontologyName = current != null ? current.getName() : "";
        List<Ontology> all = ontologyService.findAll().stream()
                .filter(o -> ontologyName.equals(o.getName()))
                .toList();
        List<Map<String, Object>> versions = new ArrayList<>();
        for (Ontology o : all) {
            Map<String, Object> v = new HashMap<>();
            v.put("id", o.getId());
            v.put("version", o.getVersion());
            v.put("versionName", o.getVersionName());
            v.put("name", o.getName());
            v.put("description", o.getDescription());
            v.put("status", o.getStatus());
            v.put("classCount", o.getClassCount());
            v.put("propertyCount", o.getPropertyCount());
            v.put("ttlPath", o.getTtlPath());
            v.put("instantiated", o.getInstantiated());
            v.put("createdAt", o.getCreatedAt());
            v.put("updatedAt", o.getUpdatedAt());
            v.put("current", o.getId().equals(current != null ? current.getId() : ""));
            v.put("ruleCount", ruleService.findByOntologyId(o.getId()).size());
            v.put("individualCount", countIndividualsFromTtl(o.getTtlPath()));
            v.put("objectPropertyCount", countObjectPropertiesFromTtl(o.getTtlPath()));
            v.put("dataPropertyCount", countDataPropertiesFromTtl(o.getTtlPath()));
            v.put("sourceMdFileName", o.getSourceMdFileName());
            v.put("sourceMdFileId", o.getSourceMdFileId());
            v.put("sourceModelName", o.getSourceModelName());
            v.put("ttlGeneratedAt", o.getTtlGeneratedAt());
            versions.add(v);
        }
        versions.sort((a, b) -> {
            String va = (String) a.get("version");
            String vb = (String) b.get("version");
            return vb != null ? compareVersions(vb, va) : -1;
        });
        return Result.success(versions);
    }

    @PostMapping("/save-version")
    @Operation(summary = "保存本体提取结果为新版本")
    public Result<Ontology> saveVersion(@RequestBody Map<String, Object> body) {
        try {
            return doSaveVersion(body);
        } catch (Exception e) {
            log.error("保存本体版本失败", e);
            String msg = e.getMessage();
            if (msg != null && msg.contains("tm_business_rule_type_check")) {
                return Result.error(500, "保存失败：业务规则类型不支持（数据库约束未包含SWRL类型），请联系管理员执行SQL修复");
            }
            return Result.error(500, "保存本体版本失败: " + (msg != null ? msg : e.getClass().getSimpleName()));
        }
    }

    @SuppressWarnings("unchecked")
    private Result<Ontology> doSaveVersion(Map<String, Object> body) throws Exception {
        String name = safeString(body.get("name"));
        String versionName = safeString(body.get("versionName"));
        String ttlContent = safeString(body.get("ttlContent"));
        if (ttlContent == null || ttlContent.isBlank()) {
            ttlContent = generateTtlFromExtraction(body);
        }
        // 将大模型生成的TTL中 Ontology 的 rdfs:label 和 owl:versionInfo 替换为对话框填写的本体模型名称和版本号名称
        if (name != null && !name.isBlank()) {
            ttlContent = ttlContent.replaceFirst(
                    "(rdfs:label\\s*\")([^\"]*)(\")",
                    "$1" + name.replace("\\", "\\\\").replace("\"", "\\\"") + "$3");
        }
        if (versionName != null && !versionName.isBlank()) {
            ttlContent = ttlContent.replaceFirst(
                    "(owl:versionInfo\\s*\")([^\"]*)(\")",
                    "$1" + versionName.replace("\\", "\\\\").replace("\"", "\\\"") + "$3");
        }
        ttlContent = cleanTtlContent(ttlContent);

        // ★ 修复 TTL 语法（中文IRI、SWRL简写等），确保 Jena 可正确解析
        String fixedTtlContent = TtlSyntaxFixer.fixAll(ttlContent);
        if (!fixedTtlContent.equals(ttlContent)) {
            log.info("TTL内容已修复（中文IRI、SWRL语法等）");
            ttlContent = fixedTtlContent;
        }

        // ★ TTL 完整性处理：始终从 JSON 数组重建类/属性段（确定性），
        // 但保留原始 LLM TTL 中的 SWRL 规则段和变量声明（LLM 生成的最有价值部分）。
        // 解决 LLM 输出截断导致属性声明不完整的问题。
        @SuppressWarnings("unchecked")
        List<Map<String, String>> objPropsCheck = (List<Map<String, String>>) body.get("objectProperties");
        @SuppressWarnings("unchecked")
        List<Map<String, String>> dataPropsCheck = (List<Map<String, String>>) body.get("dataProperties");
        int jsonObjPropCount = objPropsCheck != null ? objPropsCheck.size() : 0;
        int jsonDataPropCount = dataPropsCheck != null ? dataPropsCheck.size() : 0;
        if (jsonObjPropCount > 0 || jsonDataPropCount > 0) {
            // 1. 从原始 TTL 提取 SWRL 段（规则 + 变量声明）
            String swrlSection = extractSwrlSection(ttlContent);
            log.info("SWRL段提取结果: {} 字符, 内容预览: {}",
                    swrlSection != null ? swrlSection.length() : 0,
                    swrlSection != null ? swrlSection.replace('\n', ' ').substring(0, Math.min(200, swrlSection.length())) : "null");
            // 2. 从 JSON 数组重建完整的 TTL（类 + 对象属性 + 数据属性）
            String rebuiltTtl = generateTtlFromExtraction(body);
            // 3. 去除重建 TTL 中可能空白的 SWRL 段，追加原始 SWRL 段
            if (swrlSection != null && !swrlSection.isBlank()) {
                rebuiltTtl = removeSwrlSection(rebuiltTtl);
                rebuiltTtl = rebuiltTtl.stripTrailing() + "\n\n" + swrlSection;
                log.info("TTL从JSON重建属性段，保留原始SWRL段 ({} 字符)", swrlSection.length());
            } else {
                log.info("TTL从JSON重建属性段，原始TTL中无SWRL段，Jena将无法提取规则");
            }
            // ★ 关键：对合并后的最终 TTL 再次执行语法修复
            // fixAll 之前只在原始 TTL 上运行，合并后 SWRL 段的 Protégé 简写
            // （ClassAtom/DatavaluedPropertyAtom/BuiltinAtom）尚未被转换为标准格式。
            // 二次 fixAll 确保 Jena 能正确解析最终的 TTL。
            String secondFixedTtl = TtlSyntaxFixer.fixAll(rebuiltTtl);
            if (!secondFixedTtl.equals(rebuiltTtl)) {
                log.info("合并后 TTL 二次修复成功（SWRL简写转换等）");
            }
            ttlContent = secondFixedTtl;
        }

        // 查找同名本体的最新版本号
        String nextVersion = "1.0.0";
        List<Ontology> existing = ontologyService.findAll().stream()
                .filter(o -> name != null && name.equals(o.getName()))
                .sorted((a, b) -> compareVersions(b.getVersion(), a.getVersion()))
                .toList();
        if (!existing.isEmpty()) {
            nextVersion = incrementVersion(existing.get(0).getVersion());
        }

        // 保存 TTL 文件
        String ttlDir = storageBaseDir + "/ttl";
        String ontologyId = java.util.UUID.randomUUID().toString();
        String ttlPath = ttlDir + "/" + ontologyId + ".ttl";
        try {
            Files.createDirectories(Paths.get(ttlDir));
            Files.writeString(Paths.get(ttlPath), ttlContent, StandardCharsets.UTF_8);
        } catch (IOException e) {
            log.error("TTL 文件写入失败: path={}, error={}", ttlPath, e.getMessage(), e);
            throw new RuntimeException("TTL 文件保存失败: " + e.getMessage(), e);
        }

        // 统计
        @SuppressWarnings("unchecked")
        List<Map<String, String>> classes = (List<Map<String, String>>) body.get("classes");
        @SuppressWarnings("unchecked")
        List<Map<String, String>> objProps = (List<Map<String, String>>) body.get("objectProperties");
        @SuppressWarnings("unchecked")
        List<Map<String, String>> dataProps = (List<Map<String, String>>) body.get("dataProperties");

        // 创建本体记录
        Ontology ontology = Ontology.builder()
                .name(name)
                .description(safeString(body.getOrDefault("description", "")))
                .versionName(versionName)
                .version(nextVersion)
                .classCount(classes != null ? classes.size() : 0)
                .propertyCount(((objProps != null ? objProps.size() : 0) + (dataProps != null ? dataProps.size() : 0)))
                .ttlPath(ttlPath)
                .status(Ontology.Status.draft)
                .sourceMdFileName(safeString(body.get("sourceMdFileName")))
                .sourceMdFileId(safeString(body.get("sourceMdFileId")))
                .sourceModelName(safeString(body.get("sourceModelName")))
                .build();
        Ontology saved = ontologyService.create(ontology);

        // ★ 诊断：打印 TTL 末尾内容，便于定位 Jena EOF 解析错误
        try {
            String[] ttlLines = ttlContent.split("\n", -1);
            int totalLines = ttlLines.length;
            int previewStart = Math.max(0, totalLines - 8);
            StringBuilder diag = new StringBuilder();
            for (int i = previewStart; i < totalLines; i++) {
                diag.append(String.format("  [%d] %s\n", i + 1,
                        ttlLines[i].length() > 120 ? ttlLines[i].substring(0, 120) + "..." : ttlLines[i]));
            }
            log.info("TTL末尾诊断(saveVer) 共{}行 最后{}行:\n{}", totalLines, totalLines - previewStart, diag.toString());
        } catch (Exception ignored) {}

        // 自动加载 TTL 到 TDB2 三元组存储，使其立即可查询
        try {
            reasoningService.loadTtlContent(ttlContent, saved.getId());
            saved.setInstantiated(true);
            ontologyService.update(saved.getId(), saved);
            log.info("本体保存并自动加载到TDB2: id={}, name={}", saved.getId(), name);
        } catch (Exception e) {
            log.warn("本体已保存但自动加载TDB2失败（不影响本体保存）: id={}, error={}", saved.getId(), e.getMessage());
        }

        // 创建对象类型
        if (classes != null) {
            for (Map<String, String> cls : classes) {
                ObjectType objType = new ObjectType();
                objType.setOntologyId(saved.getId());
                objType.setIri(getWithFallback(cls, "iri", "id"));
                objType.setLabel(getWithFallback(cls, "label", "name"));
                objType.setParentIri(getParentIri(cls));
                objType.setDescription(safeString(((java.util.Map<?, ?>) cls).get("description")));
                ontologyService.createObjectType(saved.getId(), objType);
            }
        }
        // 创建对象属性（关联类型）
        // ★ 展开 owl:unionOf domain/range 为独立的类 IRI，确保前端图谱不显示
        //    "[ owl:unionOf (:A :B) ]" 而是显示实际类名
        if (objProps != null) {
            for (Map<String, String> prop : objProps) {
                Object domainRaw = ((java.util.Map<?, ?>) prop).get("domain");
                Object rangeRaw = ((java.util.Map<?, ?>) prop).get("range");
                java.util.List<String> domains = extractIrisFromDomainExpr(domainRaw);
                java.util.List<String> ranges = extractIrisFromDomainExpr(rangeRaw);

                if (domains.isEmpty()) {
                    String fallback = safeString(domainRaw);
                    if (fallback != null && !fallback.isBlank()) domains.add(fallback);
                }
                if (ranges.isEmpty()) {
                    String fallback = safeString(rangeRaw);
                    if (ranges != null && fallback != null && !fallback.isBlank()) ranges.add(fallback);
                }

                if (domains.isEmpty()) domains.add("");
                if (ranges.isEmpty()) ranges.add("");

                for (String domain : domains) {
                    for (String range : ranges) {
                        LinkType linkType = new LinkType();
                        linkType.setOntologyId(saved.getId());
                        linkType.setIri(getWithFallback(prop, "iri", "id"));
                        linkType.setLabel(getWithFallback(prop, "label", "name"));
                        linkType.setSourceType(domain);
                        linkType.setTargetType(range);
                        linkType.setDescription(safeString(((java.util.Map<?, ?>) prop).get("description")));
                        ontologyService.createLinkType(saved.getId(), linkType);
                    }
                }
            }
        }
        // 保存数据属性：按 domain 分组后写入对应 ObjectType 的 propertiesJson
        if (dataProps != null && !dataProps.isEmpty()) {
            Map<String, List<Map<String, String>>> propsByDomain = new LinkedHashMap<>();
            for (Map<String, String> prop : dataProps) {
                Object domainRaw = ((java.util.Map<?, ?>) prop).get("domain");
                // domain 可能是单个 :IRI，也可能是 [ owl:unionOf (:A :B ...) ] 表达式
                java.util.List<String> domains = extractIrisFromDomainExpr(domainRaw);
                for (String domain : domains) {
                    propsByDomain.computeIfAbsent(domain, k -> new ArrayList<>()).add(prop);
                }
            }
            List<ObjectType> objectTypes = ontologyService.findObjectTypes(saved.getId());
            attachDataPropsToClasses(propsByDomain, objectTypes);
            // 更新已修改的 ObjectType
            for (ObjectType objType : objectTypes) {
                ontologyService.updateObjectType(objType.getId(), objType);
            }
        }
        // ★ 保存业务规则 — 先从 TTL 解析 SWRL 规则，避免 JSON 中的空壳 metadata 被误存为 CONDITION
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> rules = (List<Map<String, Object>>) body.get("rules");

        // 1. 用 Jena 解析 TTL，收集其中 swrl:Imp 的 label 和 IRI
        java.util.Map<String, String> swrlImpLabelByIri2 = new java.util.LinkedHashMap<>();
        java.util.Set<String> swrlImpLabels2 = new java.util.HashSet<>();
        try {
            Model ttlModel = ModelFactory.createDefaultModel();
            ttlModel.read(new java.io.ByteArrayInputStream(ttlContent.getBytes(StandardCharsets.UTF_8)), null, "TURTLE");
            String swrlNs = "http://www.w3.org/2003/11/swrl#";
            java.util.List<org.apache.jena.rdf.model.Resource> imps = ttlModel
                    .listResourcesWithProperty(org.apache.jena.vocabulary.RDF.type, ttlModel.getResource(swrlNs + "Imp"))
                    .toList();
            for (org.apache.jena.rdf.model.Resource imp : imps) {
                String localName = extractLocalName(imp.getURI());
                String label = getStringValue(ttlModel, imp, "http://www.w3.org/2000/01/rdf-schema#label");
                if (label != null && !label.isBlank()) {
                    swrlImpLabelByIri2.put(localName, label);
                    swrlImpLabels2.add(label);
                }
            }
        } catch (Exception e) {
            log.warn("TTL 预解析 SWRL 规则失败（不影响保存）: {}", e.getMessage());
        }

        // 2. 从 TTL 直接提取 SWRL 规则到 DB
        int ttlSwrlCount2 = 0;
        try {
            Model ttlModelForSwrl = ModelFactory.createDefaultModel();
            ttlModelForSwrl.read(new java.io.ByteArrayInputStream(ttlContent.getBytes(StandardCharsets.UTF_8)), null, "TURTLE");
            ttlSwrlCount2 = extractAndPersistSwrlRules(ttlModelForSwrl, saved.getId());
            if (ttlSwrlCount2 > 0) {
                log.info("从 TTL 提取了 {} 条 SWRL 规则到 DB (saveVersion)", ttlSwrlCount2);
            }
        } catch (Exception e) {
            log.warn("从 TTL 提取 SWRL 规则失败（不影响保存）: {}", e.getMessage());
        }

        // 3. 处理 JSON rules：跳过 metadata-only stub
        if (rules != null) {
            java.util.Set<String> usedRuleNames = new java.util.HashSet<>();
            try {
                java.util.List<BusinessRule> existingSwrlRules = ruleService.findByOntologyId(saved.getId()).stream()
                        .filter(r -> r.getType() == BusinessRule.RuleType.SWRL)
                        .collect(java.util.stream.Collectors.toList());
                for (BusinessRule r : existingSwrlRules) {
                    if (r.getName() != null) usedRuleNames.add(r.getName());
                }
            } catch (Exception ignored) {}

            for (Map<String, Object> rule : rules) {
                String ruleName = resolveRuleName(rule);
                String ruleIri = rule.get("iri") != null ? rule.get("iri").toString().replaceFirst("^:", "") : "";
                String swrlRuleField = rule.get("swrlRule") != null ? rule.get("swrlRule").toString() : null;
                String jsonDescription = rule.get("description") != null ? rule.get("description").toString() : null;
                Object conditionObj = rule.get("condition");
                Object actionObj = rule.get("action");
                boolean hasSwrlExpr = swrlRuleField != null && !swrlRuleField.isBlank();
                boolean hasCondition = conditionObj != null && !conditionObj.toString().isBlank();
                boolean hasAction = actionObj != null && !actionObj.toString().isBlank();

                // ★ 如果 JSON rule 没有任何实质内容，就是 metadata stub
                //    当 TTL 已成功提取 SWRL 规则时跳过（避免重复）
                //    当 TTL 解析失败（ttlSwrlCount2==0）时，metadata 是唯一来源，必须保存
                if (!hasSwrlExpr && !hasCondition && !hasAction) {
                    if (ttlSwrlCount2 > 0) {
                        log.debug("跳过 SWRL metadata stub (saveVersion): name={}, iri={}", ruleName, ruleIri);
                        continue;
                    }
                    log.info("TTL未提取到SWRL规则，从JSON metadata保存规则 (saveVersion): name={}, iri={}", ruleName, ruleIri);
                }

                // 保存有实际内容的非 stub 规则
                String uniqueName = ruleName;
                int counter = 2;
                while (usedRuleNames.contains(uniqueName)) {
                    uniqueName = ruleName + "-" + counter;
                    counter++;
                }
                usedRuleNames.add(uniqueName);
                String ruleType = rule.getOrDefault("type", "CONDITION").toString();
                if (hasSwrlExpr) {
                    ruleType = "SWRL";
                }
                // 本体提取的规则元数据（无swrlExpr也无condition/action）必然是SWRL规则
                if (!hasSwrlExpr && !hasCondition && !hasAction && (ruleName != null || !ruleIri.isEmpty())) {
                    ruleType = "SWRL";
                }

                // ★ SWRL 规则表达式回退策略：
                //   当 JSON 未提供 swrlRule 且 TTL 解析失败时，使用自然语言描述作为 SWRL 表达式，
                //   避免 SWRL 列在前端显示为空。
                String effectiveSwrlRule = swrlRuleField;
                if ((effectiveSwrlRule == null || effectiveSwrlRule.isBlank())
                        && "SWRL".equalsIgnoreCase(ruleType)
                        && jsonDescription != null && !jsonDescription.isBlank()) {
                    effectiveSwrlRule = jsonDescription;
                    log.info("SWRL规则使用描述作为表达式回退: name={}, desc前80字={}",
                            uniqueName, jsonDescription.substring(0, Math.min(80, jsonDescription.length())));
                }

                String involvedClasses = null;
                Object involvedClassesObj = rule.get("involvedClasses");
                if (involvedClassesObj != null) {
                    involvedClasses = involvedClassesObj instanceof String ? involvedClassesObj.toString() : objectMapper.writeValueAsString(involvedClassesObj);
                }
                String involvedDataProperties = null;
                Object involvedDataPropertiesObj = rule.get("involvedDataProperties");
                if (involvedDataPropertiesObj != null) {
                    involvedDataProperties = involvedDataPropertiesObj instanceof String ? involvedDataPropertiesObj.toString() : objectMapper.writeValueAsString(involvedDataPropertiesObj);
                }
                BusinessRule businessRule = BusinessRule.builder()
                        .ontologyId(saved.getId())
                        .name(uniqueName)
                        .description(jsonDescription)
                        .type(BusinessRule.RuleType.valueOf(ruleType.toUpperCase()))
                        .conditionJson(rule.get("condition") != null ? rule.get("condition").toString() : null)
                        .actionJson(rule.get("action") != null ? rule.get("action").toString() : null)
                        .swrlRule(effectiveSwrlRule)
                        .involvedClasses(involvedClasses)
                        .involvedDataProperties(involvedDataProperties)
                        .source("自动提取")
                        .confidence(1.0)
                        .status(BusinessRule.RuleStatus.DRAFT)
                        .build();
                ruleService.create(businessRule);
            }
        }

        // 4. 用 JSON rules 中的 label/description 丰富已提取的 SWRL 规则
        if (rules != null && ttlSwrlCount2 > 0) {
            try {
                java.util.List<BusinessRule> swrlRules = ruleService.findByOntologyId(saved.getId()).stream()
                        .filter(r -> r.getType() == BusinessRule.RuleType.SWRL)
                        .collect(java.util.stream.Collectors.toList());
                for (BusinessRule swrlRule : swrlRules) {
                    for (Map<String, Object> jsonRule : rules) {
                        String jsonLabel = resolveRuleName(jsonRule);
                        String jsonIri = jsonRule.get("iri") != null ? jsonRule.get("iri").toString().replaceFirst("^:", "") : "";
                        boolean matched = false;
                        if (!jsonIri.isEmpty() && swrlRule.getName() != null) {
                            String swrlName = swrlRule.getName();
                            if (swrlName.equals(jsonIri) || swrlName.equals(jsonLabel)) {
                                matched = true;
                            }
                            String ttlLabel = swrlImpLabelByIri2.get(jsonIri);
                            if (ttlLabel != null && swrlName.equals(ttlLabel)) {
                                matched = true;
                            }
                        }
                        if (!matched && jsonLabel != null && jsonLabel.equals(swrlRule.getName())) {
                            matched = true;
                        }
                        if (matched) {
                            Object jsonDesc = jsonRule.get("description");
                            if (jsonDesc != null && !jsonDesc.toString().isBlank()) {
                                String newDesc = jsonDesc.toString();
                                log.info("规则描述覆盖(saveVer): name={}, TTL原描述={}, JSON新描述前80字={}",
                                        swrlRule.getName(),
                                        swrlRule.getDescription() != null ? swrlRule.getDescription().substring(0, Math.min(60, swrlRule.getDescription().length())) : "null",
                                        newDesc.substring(0, Math.min(80, newDesc.length())));
                                swrlRule.setDescription(newDesc);
                            } else {
                                log.warn("规则描述未覆盖(saveVer): name={}, JSON description 为空或缺失", swrlRule.getName());
                            }
                            if (jsonLabel != null && !jsonLabel.isBlank() && !jsonLabel.equals(swrlRule.getName())) {
                                swrlRule.setName(jsonLabel);
                            }
                            ruleService.update(swrlRule.getId(), swrlRule);
                            break;
                        }
                    }
                }
                log.info("已用 JSON metadata 丰富 {} 条 SWRL 规则 (saveVersion)", swrlRules.size());
            } catch (Exception e) {
                log.warn("丰富 SWRL 规则 metadata 失败 (saveVersion): {}", e.getMessage());
            }
        }

        // 新建本体跳过 syncTtlFromDb：重建后的 TTL 已完整（类/属性从JSON，SWRL从原TTL），
        // mergeTtlIncremental 的 IRI 解析格式不一致会导致属性段丢失。仅在线编辑 TTL 后才需要此同步。
        // syncTtlFromDb(saved.getId());

        return Result.success(saved);
    }

    /**
     * 解析规则名称，优先级：name字段 > label字段 > iri字段（去除冒号前缀） > "未命名规则"
     */
    private String resolveRuleName(Map<String, Object> rule) {
        Object nameObj = rule.get("name");
        if (nameObj != null) {
            String name = nameObj.toString().trim();
            if (!name.isBlank()) return name;
        }
        Object labelObj = rule.get("label");
        if (labelObj != null) {
            String label = labelObj.toString().trim();
            if (!label.isBlank()) return label;
        }
        Object iriObj = rule.get("iri");
        if (iriObj != null) {
            String iri = iriObj.toString().trim();
            if (!iri.isBlank()) {
                // 去除前导冒号，例如 :Rule_MultiClue_Base_c1_True → Rule_MultiClue_Base_c1_True
                return iri.startsWith(":") ? iri.substring(1) : iri;
            }
        }
        return "未命名规则";
    }

    // ===== 内部方法 =====

    /**
     * 从数据库数据重新生成 TTL 文件，确保 TTL 与数据库一致
     * 解决 LLM 生成的 TTL 可能遗漏 rdfs:subClassOf 等问题
     */
    private void syncTtlFromDb(String ontologyId) {
        try {
            Ontology ontology = ontologyService.findById(ontologyId);
            List<ObjectType> objectTypes = ontologyService.findObjectTypes(ontologyId);
            List<LinkType> linkTypes = ontologyService.findLinkTypes(ontologyId);

            Path ttlPath;
            if (ontology.getTtlPath() != null && !ontology.getTtlPath().isBlank()) {
                ttlPath = Paths.get(ontology.getTtlPath());
            } else {
                ttlPath = Paths.get(storageBaseDir, "ttl", ontologyId + ".ttl");
                ontology.setTtlPath(ttlPath.toString());
                ontologyService.update(ontologyId, ontology);
            }

            // ★ 在 merge 之前提取原始 SWRL 段，以便保留用户 TTL 中的 SWRL 规则
            String originalSwrlSection = null;
            if (Files.exists(ttlPath)) {
                String originalTtl = Files.readString(ttlPath, StandardCharsets.UTF_8);
                originalSwrlSection = extractSwrlSection(originalTtl);
            }

            String ttlContent;
            if (Files.exists(ttlPath)) {
                String originalTtl = Files.readString(ttlPath, StandardCharsets.UTF_8);
                ttlContent = mergeTtlIncremental(originalTtl, ontology, objectTypes, linkTypes);

                // ★ 合并后一致性校验：检查合并后的 TTL 是否保留了对象属性和类声明
                //    若 mergeTtlIncremental 因 IRI 格式不一致丢失了属性段，回退到 buildTtlFromScratch
                int ttlObjPropCount = countPatternInTtl(ttlContent, "owl:ObjectProperty");
                long dbObjPropCount = linkTypes.stream()
                        .filter(lt -> lt.getIri() != null && !lt.getIri().isBlank()
                                && !isXsdDatatypeRange(lt.getTargetType()))
                        .count();
                int ttlClassCount = countPatternInTtl(ttlContent, "owl:Class");
                long dbClassCount = objectTypes.stream()
                        .filter(ot -> ot.getIri() != null && !ot.getIri().isBlank())
                        .count();
                if ((dbObjPropCount > 0 && ttlObjPropCount == 0) || (dbClassCount > 0 && ttlClassCount == 0)) {
                    log.warn("syncTtlFromDb: mergeTtlIncremental 合并后 TTL 丢失声明 (TTL类={} DB类={}, TTL对象属性={} DB对象属性={})，" +
                            "回退到 buildTtlFromScratch 全量重建", ttlClassCount, dbClassCount, ttlObjPropCount, dbObjPropCount);
                    ttlContent = buildTtlFromScratch(ontology, objectTypes, linkTypes);
                }
            } else {
                ttlContent = buildTtlFromScratch(ontology, objectTypes, linkTypes);
            }

            // ★ 优先保留原始 SWRL 段（用户编辑 TTL 时不会破坏 SWRL 规则）
            if (originalSwrlSection != null && !originalSwrlSection.isBlank()) {
                // 移除合并后 TTL 中可能残留的 SWRL 段
                ttlContent = removeSwrlSection(ttlContent);
                ttlContent = ttlContent.trim() + "\n" + originalSwrlSection;
            } else {
                // 原始 TTL 无 SWRL 段时，从 DB 中的 BusinessRule 生成
                List<BusinessRule> allRules = ruleService.findByOntologyId(ontologyId);
                List<BusinessRule> swrlRules = allRules.stream()
                        .filter(r -> r.getType() == BusinessRule.RuleType.SWRL
                                && r.getSwrlRule() != null && !r.getSwrlRule().isBlank())
                        .collect(java.util.stream.Collectors.toList());

                if (!swrlRules.isEmpty()) {
                    // 移除 TTL 中已有的 SWRL 段（匹配带中文和纯英文的标记）
                    ttlContent = ttlContent.replaceAll(
                            "(?s)# ===== SWRL.*$", "");

                    StringBuilder swrlSection = new StringBuilder();
                    swrlSection.append("\n# ===== SWRL 规则 =====\n\n");
                    int ruleIndex = 1;
                    for (BusinessRule rule : swrlRules) {
                        Map<String, Object> ruleData = new LinkedHashMap<>();
                        ruleData.put("name", rule.getName());
                        ruleData.put("involvedClasses", rule.getInvolvedClasses());
                        ruleData.put("involvedDataProperties", rule.getInvolvedDataProperties());
                        swrlSection.append(generateSwrlImp(
                                rule.getName() != null ? rule.getName() : "Rule" + ruleIndex,
                                rule.getSwrlRule(), ruleData, ruleIndex));
                        ruleIndex++;
                    }
                    ttlContent = ttlContent.trim() + "\n" + swrlSection;
                }
            }

            Files.createDirectories(ttlPath.getParent());
            Files.writeString(ttlPath, ttlContent, StandardCharsets.UTF_8);
            log.info("已从数据库数据同步 TTL 文件: ontologyId={}, {} 个类, {} 个属性, SWRL段={}",
                    ontologyId, objectTypes.size(), linkTypes.size(),
                    originalSwrlSection != null ? "保留原始" : "从DB生成");
        } catch (Exception e) {
            log.error("同步 TTL 文件失败: ontologyId={}, error={}", ontologyId, e.getMessage(), e);
        }
    }

    /**
     * 从 TTL 文件重新解析并更新数据库记录（TTL → DB 方向同步）
     * 用于在线编辑 TTL 后保持 DB 与文件一致
     */
    private void syncDbFromTtl(String ontologyId) {
        try {
            Ontology ontology = ontologyService.findById(ontologyId);
            if (ontology.getTtlPath() == null || ontology.getTtlPath().isBlank()) {
                log.warn("syncDbFromTtl: 本体 {} 无 TTL 文件，跳过", ontologyId);
                return;
            }
            Path ttlPath = Paths.get(ontology.getTtlPath());
            if (!Files.exists(ttlPath)) {
                log.warn("syncDbFromTtl: TTL 文件不存在: {}", ttlPath);
                return;
            }

            String ttlContent = Files.readString(ttlPath, StandardCharsets.UTF_8);
            ttlContent = cleanTtlContent(ttlContent);

            // ★ 修复 TTL 语法问题（必须在 Jena 解析之前）
            ttlContent = TtlSyntaxFixer.fixAll(ttlContent);

            // Jena 解析
            Model jenaModel = ModelFactory.createDefaultModel();
            jenaModel.read(new java.io.ByteArrayInputStream(
                    ttlContent.getBytes(StandardCharsets.UTF_8)), null, "TURTLE");

            java.util.Map<String, String> prefixMap = extractPrefixesFromModel(jenaModel);

            // 收集类（显式声明 owl:Class / rdfs:Class）
            java.util.Set<org.apache.jena.rdf.model.Resource> classResources = new LinkedHashSet<>();
            jenaModel.listStatements(null, org.apache.jena.vocabulary.RDF.type, (org.apache.jena.rdf.model.RDFNode) null)
                    .filterKeep(s -> s.getObject().isURIResource()
                            && (s.getObject().asResource().getURI().equals("http://www.w3.org/2002/07/owl#Class")
                                || s.getObject().asResource().getURI().equals("http://www.w3.org/2000/01/rdf-schema#Class")))
                    .mapWith(org.apache.jena.rdf.model.Statement::getSubject)
                    .filterKeep(org.apache.jena.rdf.model.RDFNode::isURIResource)
                    .forEachRemaining(r -> classResources.add(r));
            // 也收集通过 rdfs:subClassOf 隐式声明的类（LLM 紧凑格式如 :X rdfs:subClassOf :Y .）
            jenaModel.listStatements(null,
                    jenaModel.getProperty("http://www.w3.org/2000/01/rdf-schema#subClassOf"),
                    (org.apache.jena.rdf.model.RDFNode) null)
                    .mapWith(org.apache.jena.rdf.model.Statement::getSubject)
                    .filterKeep(org.apache.jena.rdf.model.RDFNode::isURIResource)
                    .forEachRemaining(r -> classResources.add(r));

            // 收集属性
            java.util.Set<org.apache.jena.rdf.model.Resource> propResources = new LinkedHashSet<>();
            jenaModel.listStatements(null, org.apache.jena.vocabulary.RDF.type, (org.apache.jena.rdf.model.RDFNode) null)
                    .filterKeep(s -> s.getObject().isURIResource()
                            && (s.getObject().asResource().getURI().equals("http://www.w3.org/2002/07/owl#ObjectProperty")
                                || s.getObject().asResource().getURI().equals("http://www.w3.org/2002/07/owl#DatatypeProperty")
                                || s.getObject().asResource().getURI().equals("http://www.w3.org/2002/07/owl#AnnotationProperty")))
                    .mapWith(org.apache.jena.rdf.model.Statement::getSubject)
                    .filterKeep(org.apache.jena.rdf.model.RDFNode::isURIResource)
                    .forEachRemaining(r -> propResources.add(r));

            // 构建解析出的类 IRI 集合
            java.util.Set<String> parsedClassIris = new LinkedHashSet<>();
            java.util.Map<String, ObjectType> parsedClassMap = new LinkedHashMap<>();
            for (org.apache.jena.rdf.model.Resource classRes : classResources) {
                String iri = classRes.getURI();
                if (iri.contains("Ontology")) continue;
                String label = getStringValue(jenaModel, classRes, "http://www.w3.org/2000/01/rdf-schema#label");
                if (label == null || label.isBlank()) label = extractLocalName(iri);
                String description = getStringValue(jenaModel, classRes, "http://www.w3.org/2000/01/rdf-schema#comment");
                String parentIri = null;
                org.apache.jena.rdf.model.StmtIterator subIter = jenaModel.listStatements(
                        classRes, jenaModel.getProperty("http://www.w3.org/2000/01/rdf-schema#subClassOf"),
                        (org.apache.jena.rdf.model.RDFNode) null);
                if (subIter.hasNext()) {
                    org.apache.jena.rdf.model.Statement subStmt = subIter.next();
                    if (subStmt.getObject().isURIResource()) {
                        parentIri = subStmt.getObject().asResource().getURI();
                    }
                }
                ObjectType objType = ObjectType.builder()
                        .iri(iri).label(label).parentIri(parentIri).description(description).build();
                parsedClassIris.add(iri);
                parsedClassMap.put(iri, objType);
            }

            // 构建解析出的属性 IRI 集合（分离 ObjectProperty 和 DatatypeProperty）
            java.util.Set<String> parsedObjPropIris = new LinkedHashSet<>();
            java.util.Map<String, LinkType> parsedObjPropMap = new LinkedHashMap<>();
            java.util.Map<String, java.util.List<java.util.Map<String, String>>> parsedDataPropsByDomain = new LinkedHashMap<>();

            String objPropUri = "http://www.w3.org/2002/07/owl#ObjectProperty";
            String dtPropUri = "http://www.w3.org/2002/07/owl#DatatypeProperty";
            for (org.apache.jena.rdf.model.Resource propRes : propResources) {
                String iri = propRes.getURI();
                String label = getStringValue(jenaModel, propRes, "http://www.w3.org/2000/01/rdf-schema#label");
                if (label == null || label.isBlank()) label = extractLocalName(iri);
                String description = getStringValue(jenaModel, propRes, "http://www.w3.org/2000/01/rdf-schema#comment");

                String domain = null;
                org.apache.jena.rdf.model.Statement domStmt = propRes.getProperty(
                        jenaModel.getProperty("http://www.w3.org/2000/01/rdf-schema#domain"));
                if (domStmt != null && domStmt.getObject().isURIResource()) {
                    domain = domStmt.getObject().asResource().getURI();
                }

                String range = null;
                org.apache.jena.rdf.model.Statement rangeStmt = propRes.getProperty(
                        jenaModel.getProperty("http://www.w3.org/2000/01/rdf-schema#range"));
                if (rangeStmt != null) {
                    if (rangeStmt.getObject().isURIResource()) {
                        range = rangeStmt.getObject().asResource().getURI();
                    } else if (rangeStmt.getObject().isLiteral()) {
                        range = rangeStmt.getObject().asLiteral().getString();
                    }
                }

                boolean isObjProp = jenaModel.contains(propRes, org.apache.jena.vocabulary.RDF.type,
                        jenaModel.getResource(objPropUri));
                if (isObjProp) {
                    LinkType linkType = LinkType.builder()
                            .iri(iri).label(label).description(description)
                            .sourceType(domain).targetType(range).build();
                    parsedObjPropIris.add(iri);
                    parsedObjPropMap.put(iri, linkType);
                } else {
                    // DatatypeProperty → 按 domain 分组
                    java.util.Map<String, String> propEntry = new LinkedHashMap<>();
                    propEntry.put("iri", iri);
                    propEntry.put("label", label);
                    propEntry.put("dataType", range != null ? range : "xsd:string");
                    if (description != null && !description.isBlank()) {
                        propEntry.put("description", description);
                    }
                    if (domain != null && !domain.isBlank()) {
                        propEntry.put("domain", domain);
                    }
                    String domainKey = (domain != null && !domain.isBlank()) ? domain : "";
                    parsedDataPropsByDomain.computeIfAbsent(domainKey, k -> new java.util.ArrayList<>()).add(propEntry);
                }
            }

            // ===== 合并到数据库 =====
            java.util.List<ObjectType> existingTypes = ontologyService.findObjectTypes(ontologyId);
            java.util.Map<String, ObjectType> existingTypeByIri = new LinkedHashMap<>();
            for (ObjectType ot : existingTypes) {
                if (ot.getIri() != null) existingTypeByIri.put(ot.getIri(), ot);
            }

            // 更新/创建类
            for (java.util.Map.Entry<String, ObjectType> entry : parsedClassMap.entrySet()) {
                String iri = entry.getKey();
                ObjectType parsed = entry.getValue();
                ObjectType existing = existingTypeByIri.get(iri);
                if (existing != null) {
                    existing.setLabel(parsed.getLabel());
                    existing.setParentIri(parsed.getParentIri());
                    existing.setDescription(parsed.getDescription());
                    ontologyService.updateObjectType(existing.getId(), existing);
                } else {
                    parsed.setOntologyId(ontologyId);
                    ontologyService.createObjectType(ontologyId, parsed);
                }
            }

            // 删除不再存在的类
            // ★ 保护性检查：若 TTL 解析结果为 0 个类但 DB 中已有数据，说明 TTL 解析可能异常，
            //    跳过删除以避免误清空用户数据（常见于 mergeTtlIncremental IRI 格式不一致导致类段丢失）
            if (parsedClassIris.isEmpty() && !existingTypes.isEmpty()) {
                log.warn("syncDbFromTtl: TTL 中未解析到任何类 (owl:Class) 但 DB 中有 {} 条 ObjectType 记录，" +
                        "可能是 TTL 解析异常或 IRI 格式不一致，跳过类删除以保护数据", existingTypes.size());
            } else {
                for (ObjectType existing : existingTypes) {
                    if (existing.getIri() != null && !parsedClassIris.contains(existing.getIri())) {
                        ontologyService.deleteObjectType(existing.getId());
                    }
                }
            }

            // 更新/创建对象属性
            java.util.List<LinkType> existingLinks = ontologyService.findLinkTypes(ontologyId);
            java.util.Map<String, LinkType> existingLinkByIri = new LinkedHashMap<>();
            for (LinkType lt : existingLinks) {
                if (lt.getIri() != null) existingLinkByIri.put(lt.getIri(), lt);
            }

            for (java.util.Map.Entry<String, LinkType> entry : parsedObjPropMap.entrySet()) {
                String iri = entry.getKey();
                LinkType parsed = entry.getValue();
                LinkType existing = existingLinkByIri.get(iri);
                if (existing != null) {
                    existing.setLabel(parsed.getLabel());
                    existing.setSourceType(parsed.getSourceType());
                    existing.setTargetType(parsed.getTargetType());
                    existing.setDescription(parsed.getDescription());
                    ontologyService.updateLinkType(existing.getId(), existing);
                } else {
                    parsed.setOntologyId(ontologyId);
                    ontologyService.createLinkType(ontologyId, parsed);
                }
            }

            // 删除不再存在的对象属性
            // ★ 保护性检查：若 TTL 解析结果为 0 个 ObjectProperty 但 DB 中已有 LinkType 数据，
            //    说明 TTL 解析可能异常（mergeTtlIncremental IRI 格式不一致会导致属性段丢失），
            //    跳过删除以避免误清空用户精修后的关系数据
            if (parsedObjPropIris.isEmpty() && !existingLinks.isEmpty()) {
                log.warn("syncDbFromTtl: TTL 中未解析到任何 ObjectProperty 但 DB 中有 {} 条 LinkType 记录，" +
                        "可能是 TTL 解析异常或 IRI 格式不一致导致属性段丢失，跳过对象属性删除以保护数据",
                        existingLinks.size());
            } else {
                for (LinkType existing : existingLinks) {
                    if (existing.getIri() != null && !parsedObjPropIris.contains(existing.getIri())) {
                        ontologyService.deleteLinkType(existing.getId());
                    }
                }
            }

            // 更新 DatatypeProperty → propertiesJson
            java.util.List<ObjectType> refreshedTypes = ontologyService.findObjectTypes(ontologyId);
            java.util.Map<String, ObjectType> refreshedTypeByIri = new LinkedHashMap<>();
            for (ObjectType ot : refreshedTypes) {
                if (ot.getIri() != null) refreshedTypeByIri.put(ot.getIri(), ot);
            }
            // 先清空所有 propertiesJson
            for (ObjectType ot : refreshedTypes) {
                if (ot.getPropertiesJson() != null && !ot.getPropertiesJson().isBlank()) {
                    ot.setPropertiesJson(null);
                    ontologyService.updateObjectType(ot.getId(), ot);
                }
            }
            // 写入新的
            if (!parsedDataPropsByDomain.isEmpty()) {
                for (java.util.Map.Entry<String, java.util.List<java.util.Map<String, String>>> entry : parsedDataPropsByDomain.entrySet()) {
                    String domainIri = entry.getKey();
                    ObjectType objType = domainIri.isEmpty() ? null : refreshedTypeByIri.get(domainIri);
                    if (objType != null) {
                        objType.setPropertiesJson(objectMapper.writeValueAsString(entry.getValue()));
                        ontologyService.updateObjectType(objType.getId(), objType);
                    }
                }
            }

            // 更新统计
            ontology.setClassCount(parsedClassIris.size());
            ontology.setPropertyCount(parsedObjPropIris.size() + parsedDataPropsByDomain.values().stream().mapToInt(java.util.List::size).sum());
            ontologyService.update(ontologyId, ontology);

            // ★ 同时从 TTL 中提取 SWRL 规则并持久化到 DB
            try {
                int swrlCount = extractAndPersistSwrlRules(jenaModel, ontologyId);
                if (swrlCount > 0) {
                    log.info("syncDbFromTtl: 从 TTL 提取了 {} 条 SWRL 规则到 DB", swrlCount);
                }
            } catch (Exception swrlEx) {
                log.warn("syncDbFromTtl: SWRL 规则提取失败 (不影响其他同步): ontologyId={}, error={}",
                        ontologyId, swrlEx.getMessage());
            }

            log.info("已从 TTL 文件同步数据库: ontologyId={}, {} 个类, {} 个对象属性, {} 个数据属性",
                    ontologyId, parsedClassIris.size(), parsedObjPropIris.size(),
                    parsedDataPropsByDomain.values().stream().mapToInt(java.util.List::size).sum());
        } catch (Exception e) {
            log.error("从 TTL 同步数据库失败: ontologyId={}, error={}", ontologyId, e.getMessage(), e);
        }
    }

    /**
     * 清洗 TTL 内容：移除 LLM 可能混入的 JSON 结构体和 markdown 代码块标记
     */
    /**
     * 清理 IRI 字符串：移除 LLM 错误添加的 &lt;:Name&gt; 尖括号包装。
     * 例如 &lt;:DistributionTransformerMonthly&gt; → :DistributionTransformerMonthly
     */
    private String cleanIri(String iri) {
        if (iri == null || iri.isBlank()) return iri;
        return iri.replaceAll("<\\s*:([a-zA-Z_][\\w\\-]*)\\s*>", ":$1").trim();
    }

    private String cleanTtlContent(String ttl) {
        if (ttl == null || ttl.isBlank()) return ttl;
        // 移除 markdown 代码块标记
        ttl = ttl.replaceAll("```ttl\\s*", "").replaceAll("```\\s*$", "");
        // 仅在内容疑似 JSON 包裹时执行 JSON 反转义，避免损坏合法 TTL 字符串字面量
        if (ttl.startsWith("{") || (ttl.startsWith("\"") && ttl.contains("\\\\\""))) {
            ttl = ttl.replace("\\\"", "\"").replace("\\n", "\n").replace("\\t", "\t").replace("\\\\", "\\");
        }
        // 如果整个内容被包裹在 JSON 字符串引号内（如前端的 raw_text 降级），尝试提取纯 TTL
        if (ttl.startsWith("\"") && ttl.endsWith("\"") && !ttl.contains("@prefix")) {
            ttl = ttl.substring(1, ttl.length() - 1);
            ttl = ttl.replace("\\\"", "\"").replace("\\n", "\n");
        }
        return ttl.trim();
    }

    /**
     * 确保 TTL 内容中声明了指定前缀。
     * 如果前缀已被声明则原样返回；如果未声明但实际使用了，则从参考前缀
     *（如默认前缀 ":" ）的命名空间推导并插入 @prefix 声明。
     *
     * @param ttl        原始 TTL 内容
     * @param prefixName 需要检查的前缀名（如 "prop"）
     * @param refPrefix  参考前缀（如 ":" 即空字符串，代表默认命名空间），
     *                   未声明前缀的命名空间将从参考前缀推导
     * @return 补充了前缀声明后的 TTL
     */
    private String ensurePrefixDeclared(String ttl, String prefixName, String refPrefix) {
        if (ttl == null || ttl.isBlank()) return ttl;

        // 1. 检查是否已声明
        if (ttl.matches("(?s).*@prefix\\s+" + java.util.regex.Pattern.quote(prefixName) + "\\s*:.*")) {
            return ttl; // 已声明
        }

        // 2. 检查是否实际使用了该前缀（不在 @prefix 声明中的 prop: 使用）
        String usePattern = "(?<!@prefix\\s)" + java.util.regex.Pattern.quote(prefixName) + ":";
        if (!java.util.regex.Pattern.compile(usePattern).matcher(ttl).find()) {
            return ttl; // 未使用，无需添加
        }

        // 3. 从参考前缀推导命名空间
        String baseNs = null;
        // refPrefix 如 ":" 表示默认前缀，prefixMap key 是 ""
        String mapKey = refPrefix.equals(":") ? "" : refPrefix.substring(0, refPrefix.length() - 1);
        java.util.Map<String, String> pm = extractPrefixesManually(
                java.util.Arrays.asList(ttl.split("\\n")));
        baseNs = pm.get(mapKey);
        if (baseNs == null) {
            // 回退：从任何 @prefix 声明中尝试找到默认命名空间
            java.util.regex.Matcher m = java.util.regex.Pattern.compile(
                    "@prefix\\s*:\\s*<([^>]+)>").matcher(ttl);
            if (m.find()) {
                baseNs = m.group(1);
            }
        }
        if (baseNs == null) {
            log.warn("ensurePrefixDeclared: 无法找到参考前缀 {} 的命名空间，跳过补充 {}", refPrefix, prefixName);
            return ttl;
        }

        // 4. 在最后一个 @prefix 行后插入新声明
        java.util.regex.Matcher lastPrefix = java.util.regex.Pattern.compile(
                "@prefix\\s+\\S+:\\s*<[^>]+>\\s*\\.").matcher(ttl);
        int insertPos = -1;
        while (lastPrefix.find()) {
            insertPos = lastPrefix.end();
        }

        if (insertPos <= 0) {
            log.warn("ensurePrefixDeclared: 未找到 @prefix 声明行，跳过补充 {}", prefixName);
            return ttl;
        }

        // 跳到该行末尾的换行符之后
        int lineEnd = ttl.indexOf('\n', insertPos);
        if (lineEnd < 0) lineEnd = ttl.length();
        else lineEnd++; // 包含换行符

        String decl = "@prefix " + prefixName + ": <" + baseNs + "> .\n";
        String result = ttl.substring(0, lineEnd) + decl + ttl.substring(lineEnd);
        log.info("ensurePrefixDeclared: 已补充 @prefix {}: 声明 → {}", prefixName, baseNs);
        return result;
    }

    /** 从 URI 中提取本地名称（# 或 / 后面的部分） */
    private String extractLocalName(String uri) {
        if (uri == null) return "";
        int hashIdx = uri.lastIndexOf('#');
        int slashIdx = uri.lastIndexOf('/');
        int sep = Math.max(hashIdx, slashIdx);
        return sep >= 0 && sep < uri.length() - 1 ? uri.substring(sep + 1) : uri;
    }

    /** 提取 IRI 的命名空间（# 或 / 之前的部分 + 分隔符） */
    private String extractNamespace(String uri) {
        if (uri == null) return "";
        int hashIdx = uri.lastIndexOf('#');
        int slashIdx = uri.lastIndexOf('/');
        int sep = Math.max(hashIdx, slashIdx);
        return sep >= 0 ? uri.substring(0, sep + 1) : uri;
    }

    /** 获取 Jena Model 中某个资源指定属性的字符串值 */
    private String getStringValue(Model model, org.apache.jena.rdf.model.Resource res, String propUri) {
        org.apache.jena.rdf.model.Statement stmt = res.getProperty(model.getProperty(propUri));
        if (stmt != null && stmt.getObject().isLiteral()) {
            return stmt.getObject().asLiteral().getString();
        }
        return null;
    }

    // ==================== SWRL 规则提取（从 Jena Model 到 BusinessRule 实体） ====================

    /**
     * 从 Jena 模型中提取 swrl:Imp 资源并持久化为 BusinessRule 实体。
     * 只删除旧的 SWRL 类型规则，保留其他类型（条件规则等）。
     */
    private int extractAndPersistSwrlRules(Model model, String ontologyId) {
        ruleService.deleteByOntologyIdAndType(ontologyId, BusinessRule.RuleType.SWRL);

        String swrlNs = "http://www.w3.org/2003/11/swrl#";
        String rdfNs = "http://www.w3.org/1999/02/22-rdf-syntax-ns#";

        org.apache.jena.rdf.model.Resource swrlImpType = model.getResource(swrlNs + "Imp");
        org.apache.jena.rdf.model.Resource classAtomType = model.getResource(swrlNs + "ClassAtom");
        org.apache.jena.rdf.model.Resource dataPropAtomType = model.getResource(swrlNs + "DatavaluedPropertyAtom");
        org.apache.jena.rdf.model.Resource indPropAtomType = model.getResource(swrlNs + "IndividualPropertyAtom");
        org.apache.jena.rdf.model.Resource builtinAtomType = model.getResource(swrlNs + "BuiltinAtom");
        org.apache.jena.rdf.model.Resource variableType = model.getResource(swrlNs + "Variable");

        org.apache.jena.rdf.model.Property swrlBody = model.getProperty(swrlNs + "body");
        org.apache.jena.rdf.model.Property swrlHead = model.getProperty(swrlNs + "head");
        org.apache.jena.rdf.model.Property classPredicate = model.getProperty(swrlNs + "classPredicate");
        org.apache.jena.rdf.model.Property propertyPredicate = model.getProperty(swrlNs + "propertyPredicate");
        org.apache.jena.rdf.model.Property argument1 = model.getProperty(swrlNs + "argument1");
        org.apache.jena.rdf.model.Property argument2 = model.getProperty(swrlNs + "argument2");
        org.apache.jena.rdf.model.Property builtinProp = model.getProperty(swrlNs + "builtin");
        org.apache.jena.rdf.model.Property argumentsProp = model.getProperty(swrlNs + "arguments");

        org.apache.jena.rdf.model.Property rdfFirst = model.getProperty(rdfNs + "first");
        org.apache.jena.rdf.model.Property rdfRest = model.getProperty(rdfNs + "rest");
        org.apache.jena.rdf.model.Resource rdfNil = model.getResource(rdfNs + "nil");

        List<org.apache.jena.rdf.model.Resource> swrlImps = model
                .listResourcesWithProperty(org.apache.jena.vocabulary.RDF.type, swrlImpType)
                .toList();

        int count = 0;
        for (org.apache.jena.rdf.model.Resource swrlImp : swrlImps) {
            try {
                // 提取规则名称：优先 rdfs:label，其次 rdfs:comment（仅当 comment 不像 SWRL 表达式时），最后 IRI local name
                String name = getStringValue(model, swrlImp, "http://www.w3.org/2000/01/rdf-schema#label");
                String commentText = getStringValue(model, swrlImp, "http://www.w3.org/2000/01/rdf-schema#comment");
                // 判断 comment 是否为 SWRL 表达式（包含括号和逻辑连接符）
                boolean commentIsSwrlExpr = commentText != null && commentText.contains("(")
                        && commentText.contains(")") && (commentText.contains("^") || commentText.contains("->"));
                if (name == null || name.isBlank()) {
                    // 仅当 comment 不像 SWRL 表达式时才用它作为名称
                    if (commentText != null && !commentText.isBlank() && !commentIsSwrlExpr) {
                        name = commentText;
                    }
                }
                if (name == null || name.isBlank()) {
                    name = extractLocalName(swrlImp.getURI());
                }

                // 遍历 body/head RDF 列表获取原子
                List<org.apache.jena.rdf.model.Resource> bodyAtoms = traverseRdfList(
                        model, swrlImp, swrlBody, rdfFirst, rdfRest, rdfNil);
                List<org.apache.jena.rdf.model.Resource> headAtoms = traverseRdfList(
                        model, swrlImp, swrlHead, rdfFirst, rdfRest, rdfNil);

                // 构建 SWRL 文本表达式
                String bodyText = bodyAtoms.stream()
                        .map(a -> buildAtomText(model, a, classAtomType, dataPropAtomType,
                                indPropAtomType, builtinAtomType, variableType,
                                classPredicate, propertyPredicate, argument1, argument2,
                                builtinProp, argumentsProp, rdfFirst, rdfRest, rdfNil))
                        .filter(t -> !t.isBlank())
                        .collect(java.util.stream.Collectors.joining(" ^ "));

                String headText = headAtoms.stream()
                        .map(a -> buildAtomText(model, a, classAtomType, dataPropAtomType,
                                indPropAtomType, builtinAtomType, variableType,
                                classPredicate, propertyPredicate, argument1, argument2,
                                builtinProp, argumentsProp, rdfFirst, rdfRest, rdfNil))
                        .filter(t -> !t.isBlank())
                        .collect(java.util.stream.Collectors.joining(" ^ "));

                String swrlRuleText;
                // ★ 优先使用 rdfs:comment 作为权威 SWRL 表达式（避免原子重建导致的精度丢失）
                if (commentIsSwrlExpr) {
                    swrlRuleText = commentText;
                } else if (!headText.isBlank() && !bodyText.isBlank()) {
                    swrlRuleText = bodyText + " -> " + headText;
                } else if (!bodyText.isBlank()) {
                    swrlRuleText = bodyText;
                } else if (!headText.isBlank()) {
                    swrlRuleText = headText;
                } else {
                    swrlRuleText = "SWRL规则 (无表达式)";
                }

                // 收集涉及的类 IRI（来自 ClassAtom）
                Set<String> classIris = new LinkedHashSet<>();
                for (org.apache.jena.rdf.model.Resource atom : bodyAtoms) {
                    collectAtomClassIri(model, atom, classAtomType, classPredicate, classIris);
                }
                for (org.apache.jena.rdf.model.Resource atom : headAtoms) {
                    collectAtomClassIri(model, atom, classAtomType, classPredicate, classIris);
                }

                // 收集涉及的数据属性 IRI（来自 DatavaluedPropertyAtom）
                Set<String> dataPropIris = new LinkedHashSet<>();
                for (org.apache.jena.rdf.model.Resource atom : bodyAtoms) {
                    collectAtomDataPropIri(model, atom, dataPropAtomType, propertyPredicate, dataPropIris);
                }
                for (org.apache.jena.rdf.model.Resource atom : headAtoms) {
                    collectAtomDataPropIri(model, atom, dataPropAtomType, propertyPredicate, dataPropIris);
                }

                String involvedClassesJson;
                String involvedDataPropsJson;
                try {
                    involvedClassesJson = objectMapper.writeValueAsString(new ArrayList<>(classIris));
                    involvedDataPropsJson = objectMapper.writeValueAsString(new ArrayList<>(dataPropIris));
                } catch (Exception e) {
                    involvedClassesJson = "[]";
                    involvedDataPropsJson = "[]";
                }

                // 提取规则描述：仅当 comment 不是 SWRL 表达式时才用作描述
                String description = null;
                if (commentText != null && !commentText.isBlank() && !commentIsSwrlExpr) {
                    description = commentText;
                }

                BusinessRule rule = BusinessRule.builder()
                        .ontologyId(ontologyId)
                        .name(name)
                        .description(description)
                        .type(BusinessRule.RuleType.SWRL)
                        .priority(BusinessRule.RulePriority.MEDIUM)
                        .confidence(1.0)
                        .status(BusinessRule.RuleStatus.DRAFT)
                        .source("TTL导入")
                        .swrlRule(swrlRuleText)
                        .involvedClasses(involvedClassesJson)
                        .involvedDataProperties(involvedDataPropsJson)
                        .build();

                ruleService.create(rule);
                count++;

                log.info("  提取SWRL规则: name={}, body atoms={}, head atoms={}, classes={}, dataProps={}",
                        name, bodyAtoms.size(), headAtoms.size(), classIris.size(), dataPropIris.size());

            } catch (Exception e) {
                log.warn("提取单个SWRL规则失败（继续处理后续规则）: imp={}, error={}",
                        swrlImp.getURI(), e.getMessage());
            }
        }

        return count;
    }

    /** 遍历 Jena 模型中的 RDF 集合（rdf:first/rdf:rest 链表），返回有序列表 */
    private List<org.apache.jena.rdf.model.Resource> traverseRdfList(
            Model model, org.apache.jena.rdf.model.Resource subject,
            org.apache.jena.rdf.model.Property listProp,
            org.apache.jena.rdf.model.Property rdfFirst,
            org.apache.jena.rdf.model.Property rdfRest,
            org.apache.jena.rdf.model.Resource rdfNil) {

        List<org.apache.jena.rdf.model.Resource> items = new ArrayList<>();
        org.apache.jena.rdf.model.Statement listStmt = subject.getProperty(listProp);
        if (listStmt == null) return items;

        org.apache.jena.rdf.model.Resource current = listStmt.getResource();
        Set<org.apache.jena.rdf.model.Resource> visited = new LinkedHashSet<>();

        while (current != null && !current.equals(rdfNil)) {
            if (!visited.add(current)) break;

            org.apache.jena.rdf.model.Statement firstStmt = current.getProperty(rdfFirst);
            if (firstStmt != null && firstStmt.getObject().isResource()) {
                items.add(firstStmt.getResource());
            }

            org.apache.jena.rdf.model.Statement restStmt = current.getProperty(rdfRest);
            if (restStmt != null && restStmt.getObject().isResource()) {
                current = restStmt.getResource();
            } else {
                break;
            }
        }

        return items;
    }

    /** 将单个 SWRL 原子构建为文本表示 */
    private String buildAtomText(
            Model model, org.apache.jena.rdf.model.Resource atom,
            org.apache.jena.rdf.model.Resource classAtomType,
            org.apache.jena.rdf.model.Resource dataPropAtomType,
            org.apache.jena.rdf.model.Resource indPropAtomType,
            org.apache.jena.rdf.model.Resource builtinAtomType,
            org.apache.jena.rdf.model.Resource variableType,
            org.apache.jena.rdf.model.Property classPredicate,
            org.apache.jena.rdf.model.Property propertyPredicate,
            org.apache.jena.rdf.model.Property argument1,
            org.apache.jena.rdf.model.Property argument2,
            org.apache.jena.rdf.model.Property builtinProp,
            org.apache.jena.rdf.model.Property argumentsProp,
            org.apache.jena.rdf.model.Property rdfFirst,
            org.apache.jena.rdf.model.Property rdfRest,
            org.apache.jena.rdf.model.Resource rdfNil) {

        org.apache.jena.rdf.model.Statement typeStmt = atom.getProperty(
                org.apache.jena.vocabulary.RDF.type);
        if (typeStmt == null || !typeStmt.getObject().isResource()) return "";

        String typeUri = typeStmt.getResource().getURI();

        if (classAtomType.getURI().equals(typeUri)) {
            String cls = getPredicateLocalName(atom, classPredicate);
            String var = getArgAsVarName(model, atom, argument1, variableType);
            return cls + "(?" + var + ")";

        } else if (dataPropAtomType.getURI().equals(typeUri)) {
            String prop = getPredicateLocalName(atom, propertyPredicate);
            String var1 = getArgAsVarName(model, atom, argument1, variableType);
            String arg2Str = getArgAsLiteralOrVar(model, atom, argument2, variableType);
            return prop + "(?" + var1 + ", " + arg2Str + ")";

        } else if (indPropAtomType.getURI().equals(typeUri)) {
            String prop = getPredicateLocalName(atom, propertyPredicate);
            String var1 = getArgAsVarName(model, atom, argument1, variableType);
            String var2 = getArgAsVarName(model, atom, argument2, variableType);
            return prop + "(?" + var1 + ", ?" + var2 + ")";

        } else if (builtinAtomType.getURI().equals(typeUri)) {
            String builtin = getPredicateLocalName(atom, builtinProp);
            // 遍历 arguments RDF 列表（元素可能为资源或字面量）
            List<String> argStrs = new ArrayList<>();
            org.apache.jena.rdf.model.Statement argsListStmt = atom.getProperty(argumentsProp);
            if (argsListStmt != null) {
                org.apache.jena.rdf.model.Resource current = argsListStmt.getResource();
                Set<org.apache.jena.rdf.model.Resource> visitedList = new LinkedHashSet<>();
                while (current != null && !current.equals(rdfNil)) {
                    if (!visitedList.add(current)) break;
                    org.apache.jena.rdf.model.Statement firstStmt = current.getProperty(rdfFirst);
                    if (firstStmt != null) {
                        if (firstStmt.getObject().isLiteral()) {
                            argStrs.add("\"" + firstStmt.getObject().asLiteral().getString() + "\"");
                        } else if (firstStmt.getObject().isResource()) {
                            org.apache.jena.rdf.model.Resource argRes = firstStmt.getResource();
                            String lit = getLiteralValue(model, argRes);
                            if (lit != null) {
                                argStrs.add(lit);
                            } else {
                                String varName = getVariableName(model, argRes, variableType);
                                if (varName != null) {
                                    argStrs.add("?" + varName);
                                } else {
                                    argStrs.add("?" + extractLocalName(argRes.getURI()));
                                }
                            }
                        }
                    }
                    org.apache.jena.rdf.model.Statement restStmt = current.getProperty(rdfRest);
                    if (restStmt != null && restStmt.getObject().isResource()) {
                        current = restStmt.getResource();
                    } else {
                        break;
                    }
                }
            }
            String argsStr = String.join(", ", argStrs);
            return "swrlb:" + builtin + "(" + argsStr + ")";
        }

        return "";
    }

    private String getPredicateLocalName(org.apache.jena.rdf.model.Resource atom,
                                         org.apache.jena.rdf.model.Property pred) {
        org.apache.jena.rdf.model.Statement stmt = atom.getProperty(pred);
        if (stmt != null && stmt.getObject().isResource()) {
            return extractLocalName(stmt.getResource().getURI());
        }
        return "unknown";
    }

    private String getArgAsVarName(Model model, org.apache.jena.rdf.model.Resource subject,
                                   org.apache.jena.rdf.model.Property argProp,
                                   org.apache.jena.rdf.model.Resource variableType) {
        org.apache.jena.rdf.model.Statement stmt = subject.getProperty(argProp);
        if (stmt != null && stmt.getObject().isResource()) {
            org.apache.jena.rdf.model.Resource argRes = stmt.getResource();
            String varName = getVariableName(model, argRes, variableType);
            if (varName != null) return varName;
            return extractLocalName(argRes.getURI());
        }
        return "unknown";
    }

    private String getArgAsLiteralOrVar(Model model, org.apache.jena.rdf.model.Resource subject,
                                        org.apache.jena.rdf.model.Property argProp,
                                        org.apache.jena.rdf.model.Resource variableType) {
        org.apache.jena.rdf.model.Statement stmt = subject.getProperty(argProp);
        if (stmt == null) return "\"\"";
        if (stmt.getObject().isLiteral()) {
            return "\"" + stmt.getObject().asLiteral().getString() + "\"";
        }
        if (stmt.getObject().isResource()) {
            org.apache.jena.rdf.model.Resource argRes = stmt.getResource();
            String varName = getVariableName(model, argRes, variableType);
            if (varName != null) return "?" + varName;
            return extractLocalName(argRes.getURI());
        }
        return "\"\"";
    }

    private String getVariableName(Model model, org.apache.jena.rdf.model.Resource res,
                                   org.apache.jena.rdf.model.Resource variableType) {
        if (res.isAnon()) {
            org.apache.jena.rdf.model.Statement typeStmt = res.getProperty(
                    org.apache.jena.vocabulary.RDF.type);
            if (typeStmt != null && typeStmt.getObject().isResource()
                    && variableType.getURI().equals(typeStmt.getResource().getURI())) {
                // 匿名空白节点变量，尝试从节点ID中提取名称
                String id = res.getId().getLabelString();
                if (id != null && !id.isBlank()) {
                    return id;
                }
            }
            return null;
        }
        boolean isVariable = model.contains(res, org.apache.jena.vocabulary.RDF.type, variableType);
        if (isVariable || res.getURI() != null) {
            String local = extractLocalName(res.getURI() != null ? res.getURI() : res.getId().toString());
            if (local.startsWith("Var_")) local = local.substring(4);
            return local;
        }
        return null;
    }

    private String getLiteralValue(Model model, org.apache.jena.rdf.model.Resource res) {
        // BuiltinAtom 参数的字面量值可能存储为 rdf:value 或直接是 literal
        org.apache.jena.rdf.model.Statement valStmt = res.getProperty(
                model.getProperty("http://www.w3.org/1999/02/22-rdf-syntax-ns#value"));
        if (valStmt != null && valStmt.getObject().isLiteral()) {
            return "\"" + valStmt.getObject().asLiteral().getString() + "\"";
        }
        return null;
    }

    private void collectAtomClassIri(Model model, org.apache.jena.rdf.model.Resource atom,
                                     org.apache.jena.rdf.model.Resource classAtomType,
                                     org.apache.jena.rdf.model.Property classPredicate,
                                     Set<String> classIris) {
        org.apache.jena.rdf.model.Statement typeStmt = atom.getProperty(
                org.apache.jena.vocabulary.RDF.type);
        if (typeStmt == null || !typeStmt.getObject().isResource()) return;
        if (!classAtomType.getURI().equals(typeStmt.getResource().getURI())) return;

        org.apache.jena.rdf.model.Statement predStmt = atom.getProperty(classPredicate);
        if (predStmt != null && predStmt.getObject().isResource()) {
            String iri = predStmt.getResource().getURI();
            if (iri != null) classIris.add(iri);
        }
    }

    private void collectAtomDataPropIri(Model model, org.apache.jena.rdf.model.Resource atom,
                                        org.apache.jena.rdf.model.Resource dataPropAtomType,
                                        org.apache.jena.rdf.model.Property propertyPredicate,
                                        Set<String> dataPropIris) {
        org.apache.jena.rdf.model.Statement typeStmt = atom.getProperty(
                org.apache.jena.vocabulary.RDF.type);
        if (typeStmt == null || !typeStmt.getObject().isResource()) return;
        if (!dataPropAtomType.getURI().equals(typeStmt.getResource().getURI())) return;

        org.apache.jena.rdf.model.Statement predStmt = atom.getProperty(propertyPredicate);
        if (predStmt != null && predStmt.getObject().isResource()) {
            String iri = predStmt.getResource().getURI();
            if (iri != null) dataPropIris.add(iri);
        }
    }

    // ==================== SWRL 规则提取 END ====================

    /** TTL 字符串字面量转义 */
    private String escapeTtlString(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
    }

    /**
     * 将 domain/range 字符串格式化为合法的 Turtle class expression。
     * - 如果是简单的 :ClassName 前缀名，直接返回
     * - 如果包含 owl:unionOf / owl:intersectionOf / owl:oneOf，
     *   将其包装为空白节点形式 [ owl:unionOf (:Class1 :Class2 ...) ]
     * - 同时处理命名空间替换
     */
    private String formatOwlClassExpression(String expr) {
        if (expr == null || expr.isBlank()) return expr;
        // 命名空间替换
        String result = expr
                .replace("http://data2ontology.local/ontology/", ":")
                .replace("http://ontologybuilder.local/ontology#", ":")
                .replaceAll("<\\s*(:[A-Za-z_][\\w\\-]*)\\s*>", ":$1");
        // 检测 owl:unionOf / owl:intersectionOf / owl:oneOf
        java.util.regex.Pattern owlPattern = java.util.regex.Pattern.compile(
            "^(owl:(?:unionOf|intersectionOf|oneOf|complementOf))\\s*\\(([^)]*)\\)$");
        java.util.regex.Matcher m = owlPattern.matcher(result.trim());
        if (m.find()) {
            String construct = m.group(1);
            String members = m.group(2).trim().replaceAll("\\s+", " ");
            return "[ " + construct + " (" + members + ") ]";
        }
        return result;
    }

    /**
     * 更新 TTL 中 owl:Ontology 声明块的 rdfs:label 和 owl:versionInfo。
     * 仅在 Ontology 声明块（a owl:Ontology ... .）范围内操作，其他地方不做任何修改。
     */
    private String updateTtlLabelAndVersion(String ttl, String label, String versionInfo) {
        if (ttl == null || ttl.isBlank()) return ttl;

        String safeLabel = (label != null && !label.isBlank()) ? label.replace("\\", "\\\\").replace("\"", "\\\"") : null;
        String safeVersion = (versionInfo != null && !versionInfo.isBlank()) ? versionInfo.replace("\\", "\\\\").replace("\"", "\\\"") : null;
        if (safeLabel == null && safeVersion == null) return ttl;

        String[] lines = ttl.split("\n", -1);

        // 第一步：找到 owl:Ontology 声明行
        int ontStart = -1;
        for (int i = 0; i < lines.length; i++) {
            String t = lines[i].trim();
            if (t.matches(".*\\ba\\s+(?:owl:)?Ontology\\b.*") || t.matches(".*rdf:type\\s+(?:owl:)?Ontology\\b.*")) {
                ontStart = i;
                break;
            }
        }
        if (ontStart < 0) return ttl;

        // 第二步：找到 Ontology 块的终止行（第一个以 '.' 结尾的行）
        int ontEnd = -1;
        for (int i = ontStart; i < lines.length; i++) {
            if (lines[i].trim().endsWith(".")) {
                ontEnd = i;
                break;
            }
        }
        if (ontEnd < 0) return ttl;

        // 第三步：在 Ontology 块内部（含终止行），用正则就地替换属性值
        boolean labelReplaced = (safeLabel == null);
        boolean versionReplaced = (safeVersion == null);
        for (int i = ontStart; i <= ontEnd; i++) {
            if (!labelReplaced && lines[i].contains("rdfs:label")) {
                lines[i] = lines[i].replaceFirst("(rdfs:label\\s*\")[^\"]*(\")", "$1" + safeLabel + "$2");
                labelReplaced = true;
            }
            if (!versionReplaced && lines[i].contains("owl:versionInfo")) {
                lines[i] = lines[i].replaceFirst("(owl:versionInfo\\s*\")[^\"]*(\")", "$1" + safeVersion + "$2");
                versionReplaced = true;
            }
        }

        // 第四步：如果原始块中没有找到对应属性，需要插入
        if (!labelReplaced || !versionReplaced) {
            // 构建要插入的属性行
            java.util.List<String> newPropLines = new java.util.ArrayList<>();
            if (!labelReplaced) newPropLines.add("    rdfs:label \"" + safeLabel + "\" ;");
            if (!versionReplaced) newPropLines.add("    owl:versionInfo \"" + safeVersion + "\" ;");

            // 将 Ontology 声明块的最后一行（终止行）的 . 改为 ; 以实现延续
            String lastLine = lines[ontEnd].stripTrailing();
            if (lastLine.endsWith(".")) {
                lines[ontEnd] = lastLine.substring(0, lastLine.length() - 1).stripTrailing() + " ;";
            }

            // 设置最后一个新属性的终止符为 .
            String lastNewProp = newPropLines.get(newPropLines.size() - 1);
            if (lastNewProp.endsWith(" ;")) {
                newPropLines.set(newPropLines.size() - 1, lastNewProp.substring(0, lastNewProp.length() - 2) + " .");
            }

            java.util.List<String> result = new java.util.ArrayList<>();
            for (int i = 0; i < lines.length; i++) {
                result.add(lines[i]);
                if (i == ontEnd) {
                    result.addAll(newPropLines);
                }
            }
            return String.join("\n", result);
        }

        return String.join("\n", lines);
    }

    /**
     * 语义化版本号比较：按 major.minor.patch 逐段数值比较，避免字符串字典序导致的错误
     * （例如 "1.0.9" > "1.0.12" 在字典序中成立，但语义上 1.0.9 < 1.0.12）
     * @return 负数表示 v1 < v2，零表示相等，正数表示 v1 > v2
     */
    private int compareVersions(String v1, String v2) {
        if (v1 == null && v2 == null) return 0;
        if (v1 == null) return -1;
        if (v2 == null) return 1;
        try {
            String[] p1 = v1.split("\\.");
            String[] p2 = v2.split("\\.");
            int maxLen = Math.max(p1.length, p2.length);
            for (int i = 0; i < maxLen; i++) {
                int n1 = i < p1.length ? Integer.parseInt(p1[i]) : 0;
                int n2 = i < p2.length ? Integer.parseInt(p2[i]) : 0;
                if (n1 != n2) return n1 - n2;
            }
            return 0;
        } catch (NumberFormatException e) {
            // 非标准版本号时回退到字符串比较
            return v1.compareTo(v2);
        }
    }

    private String incrementVersion(String version) {
        try {
            String[] parts = version.split("\\.");
            int major = parts.length > 0 ? Integer.parseInt(parts[0]) : 1;
            int minor = parts.length > 1 ? Integer.parseInt(parts[1]) : 0;
            int patch = parts.length > 2 ? Integer.parseInt(parts[2]) : 0;
            return major + "." + minor + "." + (patch + 1);
        } catch (Exception e) {
            return version + ".1";
        }
    }

    // ===== 原有内部方法 =====

    private Map<String, Object> callAgentForExtraction(String markdown, String filename, String providerId, String modelId, String systemPrompt) {
        // 调用 Python Agent 服务进行 LLM 提取
        try {
            java.net.http.HttpClient client = SHARED_HTTP_CLIENT;

            Map<String, String> requestBody = new HashMap<>();
            requestBody.put("markdown", markdown);
            requestBody.put("filename", filename != null ? filename : "");
            if (providerId != null && !providerId.isBlank()) {
                requestBody.put("provider_id", providerId);
            }
            if (modelId != null && !modelId.isBlank()) {
                requestBody.put("model_id", modelId);
            }
            if (systemPrompt != null && !systemPrompt.isBlank()) {
                requestBody.put("system_prompt", systemPrompt);
            }
            String jsonBody = objectMapper.writeValueAsString(requestBody);

            java.net.http.HttpRequest request = java.net.http.HttpRequest.newBuilder()
                    .uri(java.net.URI.create(agentServiceUrl + "/agent/extract-ontology"))
                    .header("Content-Type", "application/json")
                    .POST(java.net.http.HttpRequest.BodyPublishers.ofString(jsonBody))
                    .timeout(java.time.Duration.ofSeconds(21600))
                    .build();

            java.net.http.HttpResponse<String> response = client.send(request,
                    java.net.http.HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() == 200) {
                @SuppressWarnings("unchecked")
                Map<String, Object> result = objectMapper.readValue(response.body(), Map.class);
                return result;
            }
            throw new RuntimeException("Agent 返回状态码: " + response.statusCode());
        } catch (Exception e) {
            throw new RuntimeException("Agent 调用异常: " + e.getMessage());
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> localRuleExtraction(String markdown) {
        // 本地简易提取：基于 Markdown 标题和列表结构
        List<Map<String, String>> classes = new ArrayList<>();
        List<Map<String, String>> objProps = new ArrayList<>();
        List<Map<String, String>> dataProps = new ArrayList<>();

        String[] lines = markdown.split("\\n");
        String currentClass = null;
        String classIriPrefix = "http://ontologybuilder.local/ontology#";
        String vocabIriPrefix = "http://ontologybuilder.local/ontology#";

        for (String line : lines) {
            line = line.trim();
            // # 标题作为类
            if (line.startsWith("## ") || line.startsWith("# ")) {
                String label = line.replaceFirst("^#+\\s*", "").trim();
                if (!label.isEmpty()) {
                    currentClass = label;
                    String iri = classIriPrefix + label.replaceAll("[\\s/]+", "_").toLowerCase();
                    classes.add(Map.of("iri", iri, "label", label, "description", "从 Markdown 提取"));
                }
            }
            // - 列表项作为属性
            if (line.startsWith("- ") && currentClass != null) {
                String propLabel = line.substring(2).trim().replaceAll("[:：]$", "").trim();
                if (!propLabel.isEmpty()) {
                    String propIri = vocabIriPrefix + propLabel.replaceAll("[\\s/]+", "_").toLowerCase();
                    Map<String, String> prop = new HashMap<>();
                    prop.put("iri", propIri);
                    prop.put("label", propLabel);
                    prop.put("domain", classIriPrefix + currentClass.replaceAll("[\\s/]+", "_").toLowerCase());
                    objProps.add(prop);
                }
            }
        }

        if (classes.isEmpty()) {
            classes.add(Map.of("iri", classIriPrefix + "default_class", "label", "默认类", "description", "未能从 Markdown 中提取到类定义"));
        }

        String ttlContent = generateTtlFromExtraction(Map.of(
                "classes", classes, "objectProperties", objProps, "dataProperties", dataProps));

        Map<String, Object> result = new HashMap<>();
        result.put("name", "Markdown 提取本体");
        result.put("description", "从 Markdown 文档自动提取的本体模型（本地规则提取）");
        result.put("classes", classes);
        result.put("objectProperties", objProps);
        result.put("dataProperties", dataProps);
        result.put("rules", List.of());
        result.put("ttlContent", ttlContent);
        return result;
    }

    @SuppressWarnings("unchecked")
    private String generateTtlFromExtraction(Map<String, Object> data) {
        StringBuilder ttl = new StringBuilder();
        ttl.append("@prefix : <http://ontologybuilder.local/ontology#> .\n");
        ttl.append("@prefix prop: <http://ontologybuilder.local/ontology#> .\n");
        ttl.append("@prefix rdf: <http://www.w3.org/1999/02/22-rdf-syntax-ns#> .\n");
        ttl.append("@prefix rdfs: <http://www.w3.org/2000/01/rdf-schema#> .\n");
        ttl.append("@prefix owl: <http://www.w3.org/2002/07/owl#> .\n");
        ttl.append("@prefix xsd: <http://www.w3.org/2001/XMLSchema#> .\n");
        ttl.append("@prefix swrl: <http://www.w3.org/2003/11/swrl#> .\n");
        ttl.append("@prefix swrlb: <http://www.w3.org/2003/11/swrlb#> .\n");
        ttl.append("@prefix var: <http://www.example.org/variable#> .\n\n");
        String ontologyLabel = safeString(data.getOrDefault("name", "AI Extracted Ontology"));
        String ontologyVersion = safeString(data.getOrDefault("versionName", "1.0"));
        ttl.append(":Ontology a owl:Ontology ;\n");
        ttl.append("    rdfs:label \"").append(escapeTtlString(ontologyLabel)).append("\" ;\n");
        ttl.append("    owl:versionInfo \"").append(escapeTtlString(ontologyVersion)).append("\" .\n\n");

        List<Map<String, String>> classes = (List<Map<String, String>>) data.get("classes");
        if (classes != null) {
            for (Map<String, String> cls : classes) {
                Object iriRaw = ((java.util.Map<?, ?>) cls).get("iri");
                String iri = (iriRaw != null ? safeString(iriRaw) : "")
                        .replace("http://data2ontology.local/ontology/", ":")
                        .replace("http://ontologybuilder.local/ontology#", ":")
                        .replaceAll("<\\s*(:[A-Za-z_][\\w\\-]*)\\s*>", ":$1");
                String label = safeString(((java.util.Map<?, ?>) cls).get("label"));
                if (label == null) label = "";
                String description = safeString(((java.util.Map<?, ?>) cls).get("description"));
                String parent = safeString(((java.util.Map<?, ?>) cls).get("parentIri"));
                ttl.append(iri).append(" a owl:Class ;\n");
                ttl.append("    rdfs:label \"").append(escapeTtlString(label)).append("\"");
                if (description != null && !description.isBlank()) {
                    ttl.append(" ;\n    rdfs:comment \"").append(escapeTtlString(description)).append("\"");
                }
                if (parent != null && !parent.isBlank()) {
                    String parentShort = parent
                            .replace("http://data2ontology.local/ontology/", ":")
                            .replace("http://ontologybuilder.local/ontology#", ":")
                            .replaceAll("<\\s*(:[A-Za-z_][\\w\\-]*)\\s*>", ":$1");
                    ttl.append(" ;\n    rdfs:subClassOf ").append(parentShort);
                }
                ttl.append(" .\n\n");
            }
        }

        List<Map<String, String>> objProps = (List<Map<String, String>>) data.get("objectProperties");
        if (objProps != null) {
            for (Map<String, String> prop : objProps) {
                Object iriRaw = ((java.util.Map<?, ?>) prop).get("iri");
                String iri = (iriRaw != null ? safeString(iriRaw) : "")
                        .replace("http://data2ontology.local/vocab/", "prop:")
                        .replace("http://ontologybuilder.local/ontology#", "prop:")
                        .replace("http://data2ontology.local/ontology/", "prop:")
                        .replaceAll("<\\s*(:?[A-Za-z_][\\w\\-]*)\\s*>", ":$1");
                String label = safeString(((java.util.Map<?, ?>) prop).get("label"));
                if (label == null) label = "";
                String description = safeString(((java.util.Map<?, ?>) prop).get("description"));
                String domain = safeString(((java.util.Map<?, ?>) prop).get("domain"));
                String range = safeString(((java.util.Map<?, ?>) prop).get("range"));
                ttl.append(iri).append(" a owl:ObjectProperty ;\n");
                ttl.append("    rdfs:label \"").append(escapeTtlString(label)).append("\"");
                if (description != null && !description.isBlank()) {
                    ttl.append(" ;\n    rdfs:comment \"").append(escapeTtlString(description)).append("\"");
                }
                if (domain != null && !domain.isBlank()) {
                    ttl.append(" ;\n    rdfs:domain ")
                            .append(formatOwlClassExpression(domain));
                }
                if (range != null && !range.isBlank()) {
                    ttl.append(" ;\n    rdfs:range ")
                            .append(formatOwlClassExpression(range));
                }
                ttl.append(" .\n\n");
            }
        }

        List<Map<String, String>> dataProps = (List<Map<String, String>>) data.get("dataProperties");
        if (dataProps != null) {
            for (Map<String, String> prop : dataProps) {
                Object iriRaw = ((java.util.Map<?, ?>) prop).get("iri");
                String iri = (iriRaw != null ? safeString(iriRaw) : "")
                        .replace("http://data2ontology.local/vocab/", "prop:")
                        .replace("http://ontologybuilder.local/ontology#", "prop:")
                        .replace("http://data2ontology.local/ontology/", "prop:")
                        .replaceAll("<\\s*(:?[A-Za-z_][\\w\\-]*)\\s*>", ":$1");
                String label = safeString(((java.util.Map<?, ?>) prop).get("label"));
                if (label == null) label = "";
                String description = safeString(((java.util.Map<?, ?>) prop).get("description"));
                String domain = safeString(((java.util.Map<?, ?>) prop).get("domain"));
                String range = safeString(((java.util.Map<?, ?>) prop).get("range"));
                ttl.append(iri).append(" a owl:DatatypeProperty ;\n");
                ttl.append("    rdfs:label \"").append(escapeTtlString(label)).append("\"");
                if (description != null && !description.isBlank()) {
                    ttl.append(" ;\n    rdfs:comment \"").append(escapeTtlString(description)).append("\"");
                }
                if (domain != null && !domain.isBlank()) {
                    ttl.append(" ;\n    rdfs:domain ")
                            .append(formatOwlClassExpression(domain));
                }
                if (range != null && !range.isBlank()) {
                    ttl.append(" ;\n    rdfs:range ").append(range);
                } else {
                    ttl.append(" ;\n    rdfs:range xsd:string");
                }
                ttl.append(" .\n\n");
            }
        }

        // 生成 SWRL 规则（完整的 RDF swrl:Imp 声明）
        List<Map<String, Object>> rules = (List<Map<String, Object>>) data.get("rules");
        if (rules != null && !rules.isEmpty()) {
            ttl.append("# ===== SWRL 规则 =====\n\n");
            int ruleIndex = 1;
            for (Map<String, Object> rule : rules) {
                String swrlRule = rule.get("swrlRule") != null ? rule.get("swrlRule").toString() : "";
                String ruleName = rule.get("name") != null ? rule.get("name").toString() : "Rule" + ruleIndex;
                if (!swrlRule.isBlank()) {
                    ttl.append(generateSwrlImp(ruleName, swrlRule, rule, ruleIndex));
                }
                ruleIndex++;
            }
        }

        return ttl.toString();
    }

    /**
     * 将 SWRL 规则文本表达式解析为完整的 RDF swrl:Imp 声明
     *
     * 支持解析格式示例：
     *   Product(?x) ∧ hasCategory(?x, ?y) → RestrictedProduct(?x)
     *   Person(?p) ∧ hasAge(?p, ?age) ∧ swrlb:greaterThan(?age, 18) → Adult(?p)
     *
     * @param ruleName 规则名称
     * @param swrlRule SWRL 规则文本表达式
     * @param ruleData 规则完整数据（含 involvedClasses, involvedDataProperties 等）
     * @param ruleIndex 规则序号
     * @return TTL 格式的 swrl:Imp 声明
     */
    @SuppressWarnings("unchecked")
    private String generateSwrlImp(String ruleName, String swrlRule, Map<String, Object> ruleData, int ruleIndex) {
        StringBuilder sb = new StringBuilder();

        // 规则 IRI
        String ruleIri = ":SWRLRule_" + ruleIndex;
        sb.append("### ").append(ruleName).append("\n");
        sb.append(ruleIri).append(" a swrl:Imp ;\n");
        sb.append("    rdfs:label \"").append(escapeTurtle(ruleName)).append("\" ;\n");

        // 解析规则文本中的 involvedClasses 用于补充 swrl:classPredicate
        Set<String> classIris = new java.util.LinkedHashSet<>();
        Object involvedClassesObj = ruleData.get("involvedClasses");
        if (involvedClassesObj instanceof List) {
            for (Object cls : (List<?>) involvedClassesObj) {
                String clsIri = cls.toString().trim()
                        .replace("http://data2ontology.local/ontology/", ":")
                        .replace("http://ontologybuilder.local/ontology#", ":");
                if (!clsIri.isBlank()) {
                    classIris.add(clsIri);
                }
            }
        } else if (involvedClassesObj instanceof String) {
            try {
                List<String> clsList = objectMapper.readValue((String) involvedClassesObj, List.class);
                for (String cls : clsList) {
                    String clsIri = cls.trim()
                            .replace("http://data2ontology.local/ontology/", ":")
                            .replace("http://ontologybuilder.local/ontology#", ":");
                    if (!clsIri.isBlank()) {
                        classIris.add(clsIri);
                    }
                }
            } catch (Exception ignored) {}
        }

        // 解析 involvedDataProperties
        Set<String> dataPropIris = new java.util.LinkedHashSet<>();
        Object involvedDataPropsObj = ruleData.get("involvedDataProperties");
        if (involvedDataPropsObj instanceof List) {
            for (Object prop : (List<?>) involvedDataPropsObj) {
                String propIri = prop.toString().trim()
                        .replace("http://data2ontology.local/vocab/", "prop:")
                        .replace("http://data2ontology.local/ontology/", "prop:")
                        .replace("http://ontologybuilder.local/ontology#", "prop:");
                if (!propIri.isBlank()) {
                    dataPropIris.add(propIri);
                }
            }
        } else if (involvedDataPropsObj instanceof String) {
            try {
                List<String> propList = objectMapper.readValue((String) involvedDataPropsObj, List.class);
                for (String prop : propList) {
                    String propIri = prop.trim()
                            .replace("http://data2ontology.local/vocab/", "prop:")
                            .replace("http://data2ontology.local/ontology/", "prop:")
                            .replace("http://ontologybuilder.local/ontology#", "prop:");
                    if (!propIri.isBlank()) {
                        dataPropIris.add(propIri);
                    }
                }
            } catch (Exception ignored) {}
        }

        // 从规则文本中提取类和属性（补充 involvedClasses 未覆盖的情况）
        extractClassesAndPropertiesFromRuleText(swrlRule, classIris, dataPropIris);

        // 解析 body 和 head
        String[] parts = swrlRule.split("->|→|=>");
        String bodyText = parts.length > 0 ? parts[0].trim() : "";
        String headText = parts.length > 1 ? parts[1].trim() : "";

        List<String> bodyAtoms = parseSwrlAtoms(bodyText);
        List<String> headAtoms = parseSwrlAtoms(headText);

        // 生成 swrl:body (RDF List)
        if (!bodyAtoms.isEmpty()) {
            sb.append("    swrl:body ").append(buildRdfList(bodyAtoms, ruleIndex, "body")).append(" ;\n");
        }

        // 生成 swrl:head (RDF List)
        if (!headAtoms.isEmpty()) {
            sb.append("    swrl:head ").append(buildRdfList(headAtoms, ruleIndex, "head")).append(" ;\n");
        }

        // 如果有类关联，生成 swrl:classPredicate 声明
        if (!classIris.isEmpty()) {
            sb.append("    # 涉及的类:\n");
            for (String clsIri : classIris) {
                sb.append("    #   ").append(clsIri).append("\n");
            }
        }

        // 保留原始表达式作为注释
        sb.append("    rdfs:comment \"").append(escapeTurtle(swrlRule)).append("\" .\n");

        // 生成具体的原子节点（body 和 head 各自从 0 开始编号）
        int atomCounter = 0;
        for (String atom : bodyAtoms) {
            sb.append(generateAtomTriple(ruleIndex, "body", atomCounter++, atom, classIris, dataPropIris));
        }
        atomCounter = 0; // head 索引独立从 0 开始，与 buildRdfList 一致
        for (String atom : headAtoms) {
            sb.append(generateAtomTriple(ruleIndex, "head", atomCounter++, atom, classIris, dataPropIris));
        }

        sb.append("\n");
        return sb.toString();
    }

    /**
     * 从 SWRL 规则文本中提取涉及的类名和属性名
     */
    private void extractClassesAndPropertiesFromRuleText(String swrlRule, Set<String> classIris, Set<String> dataPropIris) {
        // 匹配模式: :ClassName(?var) 或 ClassName(?var) 或 ClassName(?var1, ?var2)
        java.util.regex.Pattern classPattern = java.util.regex.Pattern.compile("\\b:?([A-Z][a-zA-Z0-9_]*)\\s*\\(");
        java.util.regex.Matcher classMatcher = classPattern.matcher(swrlRule);
        while (classMatcher.find()) {
            String clsName = classMatcher.group(1);
            // 过滤内置 SWRL 内置谓词
            if (!clsName.equals("swrlb") && !clsName.equals("swrl")) {
                classIris.add(":" + clsName);
            }
        }

        // 匹配模式: :lowercaseName(?var, ...) 或 lowercasName(?var, ...) —— 看起来像属性
        java.util.regex.Pattern propPattern = java.util.regex.Pattern.compile("\\b:?([a-z][a-zA-Z0-9_]*)\\s*\\([^)]+\\)");
        java.util.regex.Matcher propMatcher = propPattern.matcher(swrlRule);
        while (propMatcher.find()) {
            String propName = propMatcher.group(1);
            // 过滤掉 swrlb 内置函数和单参数调用
            if (propName.equals("swrlb")) continue;
            String args = propMatcher.group().substring(propMatcher.group().indexOf('(') + 1, propMatcher.group().lastIndexOf(')'));
            long argCount = args.chars().filter(c -> c == '?').count();
            if (argCount >= 1) {
                dataPropIris.add(":" + propName);
            }
        }
    }

    /**
     * 解析 SWRL 规则半部（body 或 head）中的原子
     */
    private List<String> parseSwrlAtoms(String part) {
        List<String> atoms = new java.util.ArrayList<>();
        if (part.isBlank()) return atoms;

        // 使用与 SwrlSyntaxValidator.splitAtoms 一致的方式：按 ^ 分割（处理括号嵌套）
        int depth = 0;
        StringBuilder current = new StringBuilder();
        for (int i = 0; i < part.length(); i++) {
            char c = part.charAt(i);
            if (c == '(') depth++;
            else if (c == ')') depth--;
            else if (c == '^' && depth == 0) {
                String atom = current.toString().trim();
                if (!atom.isBlank() && atom.contains("(") && atom.contains(")")) {
                    atoms.add(atom);
                }
                current = new StringBuilder();
                continue;
            }
            current.append(c);
        }
        String last = current.toString().trim();
        if (!last.isBlank() && last.contains("(") && last.contains(")")) {
            atoms.add(last);
        }
        return atoms;
    }

    /**
     * 构建 RDF List 的 Turtle 表示
     * 例如: ( :Atom1 :Atom2 :Atom3 )
     */
    private String buildRdfList(List<String> atoms, int ruleIndex, String side) {
        if (atoms.isEmpty()) return "rdf:nil";
        StringBuilder list = new StringBuilder("(");
        for (int i = 0; i < atoms.size(); i++) {
            if (i > 0) list.append(" ");
            list.append(":Atom_").append(ruleIndex).append("_").append(side).append(i);
        }
        list.append(")");
        return list.toString();
    }

    /**
     * 生成单个 SWRL 原子的三元组声明
     */
    private String generateAtomTriple(int ruleIndex, String side, int atomIndex, String atomText,
                                       Set<String> classIris, Set<String> dataPropIris) {
        StringBuilder sb = new StringBuilder();
        String atomIri = ":Atom_" + ruleIndex + "_" + side + atomIndex;

        // 解析原子: Name(?arg1, ?arg2, ...) 支持冒号前缀如 swrlb:lessThan, :hasScore
        java.util.regex.Pattern atomPattern = java.util.regex.Pattern.compile("^([\\w:]+)\\s*\\(([^)]*)\\)");
        java.util.regex.Matcher matcher = atomPattern.matcher(atomText.trim());
        if (!matcher.find()) {
            return "";
        }

        String predicateName = matcher.group(1);  // 如 "swrlb:lessThan", ":Student", "hasScore"
        String argsStr = matcher.group(2).trim();
        String[] args = argsStr.split("\\s*,\\s*");

        // 去掉前导冒号获取短名（用于 IRI 引用）
        String bareName = predicateName.startsWith(":") ? predicateName.substring(1) : predicateName;
        String iriRef = ":" + bareName;  // 使用默认命名空间前缀 :

        boolean isBuiltin = predicateName.startsWith("swrlb:");

        if (isBuiltin) {
            // swrlb 内置函数: swrlb:lessThan(?score, 50)
            String builtinFunc = predicateName.substring(6); // 去掉 "swrlb:" 前缀
            sb.append(atomIri).append(" a swrl:BuiltinAtom ;\n");
            sb.append("    swrl:builtin swrlb:").append(builtinFunc).append(" ;\n");
            sb.append("    swrl:arguments ");
            sb.append(buildRdfListForArgs(args, ruleIndex, side, atomIndex));
            sb.append(" .\n\n");

            // 生成变量声明（字面量参数已内联到 swrl:arguments 中，无需 Arg 包装）
            for (int i = 0; i < args.length; i++) {
                String arg = args[i].trim();
                if (arg.startsWith("?")) {
                    sb.append(":Var_").append(safeVarName(arg))
                      .append(" a swrl:Variable .\n");
                }
            }
            sb.append("\n");
        } else {
            // 判断是类原子还是属性原子
            // 类名以大写字母开头（Student, :Student），属性名以小写开头（hasScore, :hasScore）
            boolean isClassAtom = Character.isUpperCase(bareName.codePointAt(0))
                    && args.length >= 1;

            if (isClassAtom) {
                // 类原子: ClassName(?x) -> ClassAtom
                sb.append(atomIri).append(" a swrl:ClassAtom ;\n");
                sb.append("    swrl:classPredicate ").append(iriRef).append(" ;\n");
                sb.append("    swrl:argument1 :Var_").append(safeVarName(args[0])).append(" .\n\n");
                // 变量声明
                sb.append(":Var_").append(safeVarName(args[0])).append(" a swrl:Variable .\n\n");
            } else {
                // 属性原子: hasX(?x, ?y) 或 :hasX(?x, "literal")
                // 判断是数据属性还是对象属性（第二个参数是否为字面量）
                boolean isDatavalued = false;
                if (args.length >= 2) {
                    String arg2 = args[1].trim();
                    if (!arg2.startsWith("?")) {
                        isDatavalued = true; // 字面量参数 → 数据属性
                    }
                }
                // 也检查是否在已知数据属性列表中
                if (!isDatavalued && dataPropIris != null) {
                    for (String dp : dataPropIris) {
                        if (dp.endsWith(":" + bareName) || dp.equalsIgnoreCase(iriRef)) {
                            isDatavalued = true;
                            break;
                        }
                    }
                }

                String atomType = isDatavalued ? "swrl:DatavaluedPropertyAtom" : "swrl:IndividualPropertyAtom";
                sb.append(atomIri).append(" a ").append(atomType).append(" ;\n");
                sb.append("    swrl:propertyPredicate ").append(iriRef).append(" ;\n");
                if (args.length >= 1) {
                    sb.append("    swrl:argument1 :Var_").append(safeVarName(args[0])).append(" ;\n");
                }
                if (args.length >= 2) {
                    String arg2 = args[1].trim();
                    if (arg2.startsWith("?")) {
                        sb.append("    swrl:argument2 :Var_").append(safeVarName(arg2)).append(" .\n\n");
                        sb.append(":Var_").append(safeVarName(arg2)).append(" a swrl:Variable .\n\n");
                    } else {
                        // 字面量值：纯数字 → bare integer，字符串 → quoted literal
                        String val = extractLiteralValue(arg2);
                        if (val.matches("\\d+")) {
                            sb.append("    swrl:argument2 ").append(val).append(" .\n\n");
                        } else {
                            sb.append("    swrl:argument2 \"").append(escapeTurtle(val)).append("\" .\n\n");
                        }
                    }
                } else {
                    sb.append("    swrl:argument2 \"\" .\n\n");
                }
                if (args.length >= 1) {
                    sb.append(":Var_").append(safeVarName(args[0])).append(" a swrl:Variable .\n\n");
                }
            }
        }

        return sb.toString();
    }

    /** 从参数中提取字面量值（去除引号） */
    private String extractLiteralValue(String arg) {
        String trimmed = arg.trim();
        if ((trimmed.startsWith("\"") && trimmed.endsWith("\""))
                || (trimmed.startsWith("'") && trimmed.endsWith("'"))) {
            return trimmed.substring(1, trimmed.length() - 1);
        }
        return trimmed;
    }

    /**
     * 为 swrl:BuiltinAtom 构建参数 RDF List
     * 变量引用为 :Var_xxx，字面量值直接内联（纯数字 → bare integer，字符串 → quoted literal）
     */
    private String buildRdfListForArgs(String[] args, int ruleIndex, String side, int atomIndex) {
        StringBuilder list = new StringBuilder("(");
        for (int i = 0; i < args.length; i++) {
            if (i > 0) list.append(" ");
            String arg = args[i].trim();
            if (arg.startsWith("?")) {
                list.append(":Var_").append(safeVarName(arg));
            } else {
                // 字面量值直接内联，不再生成 :Arg_* IRI 包装
                String val = extractLiteralValue(arg);
                if (val.matches("\\d+")) {
                    list.append("\"").append(val).append("\"^^xsd:integer");
                } else {
                    list.append("\"").append(escapeTurtle(val)).append("\"");
                }
            }
        }
        list.append(")");
        return list.toString();
    }

    /**
     * 安全的变量名（去掉 ? 前缀）
     */
    private String safeVarName(String var) {
        if (var.startsWith("?")) {
            var = var.substring(1);
        }
        return var;
    }

    /**
     * 转义 Turtle 字符串中的特殊字符
     */
    private String escapeTurtle(String str) {
        if (str == null) return "";
        return str.replace("\\", "\\\\")
                  .replace("\"", "\\\"")
                  .replace("\n", "\\n")
                  .replace("\r", "\\r")
                  .replace("\t", "\\t");
    }

    @PostMapping("/{id}/regenerate-ttl")
    @Operation(summary = "增量更新 TTL 文件", description = "基于数据库精修数据，在原始 TTL 文件上增量更新，保留原始格式、顺序和注释")
    public Result<Void> regenerateTtl(@PathVariable String id) {
        try {
            Ontology ontology = ontologyService.findById(id);
            List<ObjectType> objectTypes = ontologyService.findObjectTypes(id);
            List<LinkType> linkTypes = ontologyService.findLinkTypes(id);

            // 构建 TTL 文件路径
            Path ttlPath;
            if (ontology.getTtlPath() != null && !ontology.getTtlPath().isBlank()) {
                ttlPath = Paths.get(ontology.getTtlPath());
            } else {
                ttlPath = Paths.get(storageBaseDir, "ttl", id + ".ttl");
                ontology.setTtlPath(ttlPath.toString());
                ontologyService.update(id, ontology);
            }

            String ttlContent;
            if (Files.exists(ttlPath)) {
                // 已有 TTL 文件：读取并在原始内容上增量更新
                ttlContent = new String(Files.readAllBytes(ttlPath), StandardCharsets.UTF_8);
                ttlContent = mergeTtlIncremental(ttlContent, ontology, objectTypes, linkTypes);

                // ★ 合并后一致性校验：若增量合并丢失了属性/类声明，回退到全量重建
                int ttlObjPropCount = countPatternInTtl(ttlContent, "owl:ObjectProperty");
                long dbObjPropCount = linkTypes.stream()
                        .filter(lt -> lt.getIri() != null && !lt.getIri().isBlank()
                                && !isXsdDatatypeRange(lt.getTargetType()))
                        .count();
                int ttlClassCount = countPatternInTtl(ttlContent, "owl:Class");
                long dbClassCount = objectTypes.stream()
                        .filter(ot -> ot.getIri() != null && !ot.getIri().isBlank())
                        .count();
                if ((dbObjPropCount > 0 && ttlObjPropCount == 0) || (dbClassCount > 0 && ttlClassCount == 0)) {
                    log.warn("regenerateTtl: mergeTtlIncremental 合并后 TTL 丢失声明 (TTL类={} DB类={}, TTL对象属性={} DB对象属性={})，" +
                            "回退到 buildTtlFromScratch 全量重建", ttlClassCount, dbClassCount, ttlObjPropCount, dbObjPropCount);
                    ttlContent = buildTtlFromScratch(ontology, objectTypes, linkTypes);
                }
            } else {
                // 无 TTL 文件：从数据库数据全新生成
                ttlContent = buildTtlFromScratch(ontology, objectTypes, linkTypes);
            }

            Files.createDirectories(ttlPath.getParent());
            Files.writeString(ttlPath, ttlContent, StandardCharsets.UTF_8);

            ontology.setTtlGeneratedAt(LocalDateTime.now());
            ontologyService.update(id, ontology);

            log.info("TTL 文件增量更新成功: {} ({} 个类, {} 个对象属性, {} bytes)",
                    id, objectTypes.size(), linkTypes.size(), ttlContent.length());
            return Result.success();
        } catch (Exception e) {
            log.error("增量更新 TTL 失败: id={}, error={}", id, e.getMessage(), e);
            return Result.error("增量更新 TTL 失败: " + e.getMessage());
        }
    }

    @PostMapping("/{id}/sync-swrl-rules")
    @Operation(summary = "从 TTL 文件同步 SWRL 规则到业务规则表", description = "解析本体 TTL 文件中的 swrl:Imp 规则，创建或更新 BusinessRule 实体")
    public Result<Map<String, Object>> syncSwrlRules(@PathVariable String id) {
        try {
            Ontology ontology = ontologyService.findById(id);

            Path ttlPath;
            if (ontology.getTtlPath() != null && !ontology.getTtlPath().isBlank()) {
                ttlPath = Paths.get(ontology.getTtlPath());
            } else {
                ttlPath = Paths.get(storageBaseDir, "ttl", id + ".ttl");
            }
            if (!Files.exists(ttlPath)) {
                return Result.error("TTL 文件不存在: " + ttlPath);
            }

            String ttlContent = Files.readString(ttlPath, StandardCharsets.UTF_8);
            ttlContent = TtlSyntaxFixer.fixAll(ttlContent);

            Model jenaModel = ModelFactory.createDefaultModel();
            jenaModel.read(new java.io.ByteArrayInputStream(ttlContent.getBytes(StandardCharsets.UTF_8)), null, "TURTLE");

            if (jenaModel.isEmpty()) {
                return Result.error("无法解析 TTL 文件");
            }

            // ★ 在删除旧规则前，保存现有的描述信息（按规则名称索引）
            java.util.Map<String, String> savedDescriptions = new java.util.LinkedHashMap<>();
            java.util.List<BusinessRule> oldRules = ruleService.findByOntologyId(id).stream()
                    .filter(r -> r.getType() == BusinessRule.RuleType.SWRL)
                    .collect(java.util.stream.Collectors.toList());
            long swrlCountBefore = oldRules.size();
            for (BusinessRule oldRule : oldRules) {
                if (oldRule.getDescription() != null && !oldRule.getDescription().isBlank()
                        && oldRule.getName() != null && !oldRule.getName().isBlank()) {
                    savedDescriptions.put(oldRule.getName(), oldRule.getDescription());
                }
            }
            log.info("SWRL 同步前保存描述: {} 条有描述的旧规则", savedDescriptions.size());

            // ★ 检查 TTL 中是否真的有 SWRL 规则，避免 TTL 无 SWRL 时误删 JSON 元数据保存的规则
            String swrlNs = "http://www.w3.org/2003/11/swrl#";
            int ttlSwrlImpCount = jenaModel
                    .listResourcesWithProperty(org.apache.jena.vocabulary.RDF.type, jenaModel.getResource(swrlNs + "Imp"))
                    .toList().size();
            if (ttlSwrlImpCount == 0 && swrlCountBefore > 0) {
                log.info("SWRL 同步跳过: TTL 中无 SWRL 规则 (Imp=0)，保留 DB 中已有的 {} 条规则", swrlCountBefore);
                Map<String, Object> skipResult = new LinkedHashMap<>();
                skipResult.put("syncedCount", 0);
                skipResult.put("deletedCount", 0);
                skipResult.put("skipped", true);
                skipResult.put("reason", "TTL 中无 SWRL 规则，保留 DB 中已有规则");
                return Result.success(skipResult);
            }

            int syncedCount = extractAndPersistSwrlRules(jenaModel, id);

            // ★ 从旧规则恢复描述（按名称匹配）
            if (!savedDescriptions.isEmpty()) {
                int restoredCount = 0;
                java.util.List<BusinessRule> newRules = ruleService.findByOntologyId(id).stream()
                        .filter(r -> r.getType() == BusinessRule.RuleType.SWRL)
                        .collect(java.util.stream.Collectors.toList());
                for (BusinessRule newRule : newRules) {
                    String oldDesc = savedDescriptions.get(newRule.getName());
                    if (oldDesc != null && !oldDesc.equals(newRule.getDescription())) {
                        log.info("SWRL同步恢复描述: name={}, 旧描述前60字={}",
                                newRule.getName(),
                                oldDesc.substring(0, Math.min(60, oldDesc.length())));
                        newRule.setDescription(oldDesc);
                        ruleService.update(newRule.getId(), newRule);
                        restoredCount++;
                    }
                }
                log.info("SWRL 同步恢复描述完成: 恢复 {} 条", restoredCount);
            }

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("syncedCount", syncedCount);
            result.put("deletedCount", (int) swrlCountBefore);
            log.info("SWRL 规则同步完成: ontologyId={}, 同步 {} 条, 清除旧规则 {} 条", id, syncedCount, swrlCountBefore);
            return Result.success(result);
        } catch (Exception e) {
            log.error("同步 SWRL 规则失败: id={}, error={}", id, e.getMessage(), e);
            return Result.error("同步 SWRL 规则失败: " + e.getMessage());
        }
    }

    @PostMapping("/{id}/sync-rules-to-ttl")
    @Operation(summary = "将业务规则同步回 TTL 文件", description = "读取数据库中的 BusinessRule 实体，生成 SWRL 规则写回 TTL 文件")
    public Result<Void> syncRulesToTtl(@PathVariable String id) {
        try {
            Ontology ontology = ontologyService.findById(id);
            List<BusinessRule> rules = ruleService.findByOntologyId(id);

            Path ttlPath;
            if (ontology.getTtlPath() != null && !ontology.getTtlPath().isBlank()) {
                ttlPath = Paths.get(ontology.getTtlPath());
            } else {
                ttlPath = Paths.get(storageBaseDir, "ttl", id + ".ttl");
            }
            if (!Files.exists(ttlPath)) {
                return Result.error("TTL 文件不存在: " + ttlPath);
            }

            String ttlContent = Files.readString(ttlPath, StandardCharsets.UTF_8);

            // 移除旧的 SWRL 规则段和变量声明
            ttlContent = removeSwrlSection(ttlContent);

            // 生成新的 SWRL 规则段（变量声明由各原子的 generateAtomTriple 内联生成）
            if (rules != null && !rules.isEmpty()) {
                StringBuilder swrlSection = new StringBuilder();
                swrlSection.append("\n# ===== SWRL 规则 =====\n\n");
                int ruleIndex = 1;
                for (BusinessRule rule : rules) {
                    if (rule.getSwrlRule() != null && !rule.getSwrlRule().isBlank()) {
                        Map<String, Object> ruleData = new LinkedHashMap<>();
                        ruleData.put("name", rule.getName());
                        ruleData.put("involvedClasses", rule.getInvolvedClasses());
                        ruleData.put("involvedDataProperties", rule.getInvolvedDataProperties());
                        swrlSection.append(generateSwrlImp(
                                rule.getName() != null ? rule.getName() : "Rule" + ruleIndex,
                                rule.getSwrlRule(), ruleData, ruleIndex));
                    }
                    ruleIndex++;
                }
                ttlContent = ttlContent.trim() + "\n" + swrlSection;
            }

            Files.writeString(ttlPath, ttlContent, StandardCharsets.UTF_8);
            log.info("业务规则已同步到 TTL 文件: ontologyId={}, ruleCount={}", id, rules != null ? rules.size() : 0);
            return Result.success();
        } catch (Exception e) {
            log.error("同步规则到 TTL 失败: id={}, error={}", id, e.getMessage(), e);
            return Result.error("同步规则到 TTL 失败: " + e.getMessage());
        }
    }

    /** 从 TTL 内容中移除 SWRL 规则段和变量声明（匹配 # ===== SWRL... 标记及其后所有内容） */
    private String removeSwrlSection(String ttlContent) {
        // 使用正则匹配 "# ===== SWRL" 开头的行（兼容中文标记如 "SWRL 规则（计算等级）"）
        java.util.regex.Pattern swrlSectionPattern = java.util.regex.Pattern.compile(
                "^#\\s*={4,}\\s*SWRL.*$", java.util.regex.Pattern.MULTILINE);
        java.util.regex.Matcher m = swrlSectionPattern.matcher(ttlContent);
        if (m.find()) {
            return ttlContent.substring(0, m.start()).trim();
        }

        // 没有分隔标记时，逐行移除 swrl:Imp 规则块和 swrl:Variable 声明
        StringBuilder sb = new StringBuilder();
        String[] lines = ttlContent.split("\n", -1);
        boolean inSwrlBlock = false;
        java.util.regex.Pattern swrlStartPattern = java.util.regex.Pattern.compile(
                "^[\\w:]+\\s+(a|rdf:type)\\s+swrl:Imp\\s*;.*");
        java.util.regex.Pattern varDeclPattern = java.util.regex.Pattern.compile(
                "^(var:)?\\w+\\s+a\\s+swrl:Variable\\s*\\.");

        for (String line : lines) {
            if (varDeclPattern.matcher(line.trim()).matches()) continue;
            if (swrlStartPattern.matcher(line.trim()).matches()) {
                inSwrlBlock = true;
                continue;
            }
            if (inSwrlBlock) {
                if (line.trim().endsWith(".") && !line.trim().endsWith(";")) {
                    inSwrlBlock = false;
                }
                continue;
            }
            sb.append(line).append("\n");
        }
        return sb.toString().trim();
    }

    /**
     * 统计 TTL 内容中指定模式（如 owl:ObjectProperty）的出现次数。
     * 用于增量合并后的一致性校验，避免合并导致属性/类声明丢失。
     */
    private int countPatternInTtl(String ttlContent, String pattern) {
        if (ttlContent == null || ttlContent.isBlank()) return 0;
        int count = 0;
        int idx = 0;
        while ((idx = ttlContent.indexOf(pattern, idx)) != -1) {
            count++;
            idx += pattern.length();
        }
        return count;
    }

    /**
     * 从 TTL 内容中提取 SWRL 规则段（从 "# ===== SWRL" 标记开始到文件末尾）。
     * 如果没有找到 SWRL 标记，尝试检测 swrl:Imp 声明作为回退。
     * @return SWRL 段文本（含前导换行），如果没有则返回 null
     */
    private String extractSwrlSection(String ttlContent) {
        if (ttlContent == null || ttlContent.isBlank()) return null;

        // 优先匹配 "# ===== SWRL" 标记（兼容中文）
        java.util.regex.Pattern swrlMarker = java.util.regex.Pattern.compile(
                "^#\\s*={4,}\\s*SWRL.*$", java.util.regex.Pattern.MULTILINE);
        java.util.regex.Matcher m = swrlMarker.matcher(ttlContent);
        if (m.find()) {
            return "\n" + ttlContent.substring(m.start());
        }

        // 回退：检测 swrl:Imp 声明，从第一个 swrl:Imp 之前的变量声明或注释行开始提取
        java.util.regex.Pattern impPattern = java.util.regex.Pattern.compile(
                "^[\\w:]+\\s+(a|rdf:type)\\s+swrl:Imp\\s*[;.]", java.util.regex.Pattern.MULTILINE);
        java.util.regex.Matcher impMatcher = impPattern.matcher(ttlContent);
        if (impMatcher.find()) {
            int impStart = impMatcher.start();
            // 向前查找 SWRL 段的真正起点：优先找注释标记，其次找第一个变量声明，
            // 确保变量声明不被丢失在主 TTL 中
            int sectionStart = impStart;
            java.util.regex.Pattern varDeclPattern = java.util.regex.Pattern.compile(
                    "^(var:)?\\w+\\s+a\\s+swrl:Variable\\s*\\.");
            String[] lines = ttlContent.substring(0, impStart).split("\n", -1);
            int varDeclStart = -1; // 记录第一个变量声明的行号
            for (int i = lines.length - 1; i >= 0; i--) {
                String line = lines[i].trim();
                if (line.startsWith("###") || line.startsWith("# ===== SWRL")
                        || (line.startsWith("#") && line.contains("SWRL"))) {
                    int pos = 0;
                    for (int j = 0; j < i; j++) pos += lines[j].length() + 1;
                    sectionStart = pos;
                    break;
                }
                // 记录变量声明行号，继续向前找到第一个变量声明
                if (varDeclPattern.matcher(line).matches()) {
                    varDeclStart = i;
                }
                // 遇到空行且已找到变量声明时停止，以变量声明为起点
                if (line.isEmpty() && varDeclStart >= 0) {
                    int pos = 0;
                    for (int j = 0; j < varDeclStart; j++) pos += lines[j].length() + 1;
                    sectionStart = pos;
                    break;
                }
            }
            // 如果没有找到注释标记，但找到了变量声明，从变量声明开始
            if (sectionStart == impStart && varDeclStart >= 0) {
                int pos = 0;
                for (int j = 0; j < varDeclStart; j++) pos += lines[j].length() + 1;
                sectionStart = pos;
            }
            return "\n" + ttlContent.substring(sectionStart);
        }

        return null;
    }

    /**
     * 从 TTL 行列表中提取一个完整的语句块（从 startLine 到第一个以 "." 结尾的行）。
     */
    private String extractTtlBlock(List<String> lines, int startLine) {
        StringBuilder sb = new StringBuilder();
        for (int j = startLine; j < lines.size(); j++) {
            sb.append(lines.get(j)).append("\n");
            if (lines.get(j).trim().endsWith(".")) break;
        }
        return sb.toString();
    }

    /**
     * 从 TTL 块中提取谓词的字面量值（如 rdfs:label "学生"）。
     */
    private String extractTtlLiteral(String block, String predicate) {
        java.util.regex.Pattern p = java.util.regex.Pattern.compile(
                java.util.regex.Pattern.quote(predicate) + "\\s+\"([^\"]*)\"");
        java.util.regex.Matcher m = p.matcher(block);
        if (m.find()) return m.group(1);
        return null;
    }

    /**
     * 从 TTL 块中提取谓词的 IRI 引用值（如 rdfs:domain :Student）。
     */
    private String extractTtlIriRef(String block, String predicate) {
        // 匹配 :prefixName 或 <fullIRI>
        java.util.regex.Pattern p = java.util.regex.Pattern.compile(
                java.util.regex.Pattern.quote(predicate) + "\\s+([\\w:]+|<[^>]+>)");
        java.util.regex.Matcher m = p.matcher(block);
        if (m.find()) return m.group(1);
        return null;
    }

    /** null-safe string equality */
    private boolean nullSafeEquals(String a, String b) {
        if (a == null) a = "";
        if (b == null) b = "";
        return a.equals(b);
    }

    /**
     * 在原始 TTL 内容上增量合并数据库精修数据，保留原始格式、顺序和注释
     */
    private String mergeTtlIncremental(String originalTtl, Ontology ontology,
                                         List<ObjectType> objectTypes, List<LinkType> linkTypes) {
        // 解析原始 TTL，按区块分割
        List<String> originalLines = new ArrayList<>(java.util.Arrays.asList(originalTtl.split("\\n", -1)));

        // 用 Jena 解析获取完整的前缀映射
        java.util.Map<String, String> prefixMap = new java.util.LinkedHashMap<>();
        try {
            Model model = ModelFactory.createDefaultModel();
            model.read(new java.io.ByteArrayInputStream(originalTtl.getBytes(StandardCharsets.UTF_8)), null, "TURTLE");
            prefixMap.putAll(model.getNsPrefixMap());
        } catch (Exception e) {
            log.warn("Jena 解析原始 TTL 失败，回退到手动提取前缀: {}", e.getMessage());
            prefixMap = extractPrefixesManually(originalLines);
        }
        // 确保基础前缀存在
        prefixMap.putIfAbsent("rdf", "http://www.w3.org/1999/02/22-rdf-syntax-ns#");
        prefixMap.putIfAbsent("rdfs", "http://www.w3.org/2000/01/rdf-schema#");
        prefixMap.putIfAbsent("owl", "http://www.w3.org/2002/07/owl#");
        prefixMap.putIfAbsent("xsd", "http://www.w3.org/2001/XMLSchema#");

        // effectively-final snapshot for use in lambda（避免 prefixMap 在 catch 中被重赋值导致非 effectively final）
        final java.util.Map<String, String> resolvedPrefixMap = prefixMap;

        // ★ IRI 标准化：将 DB 中可能为前缀名（如 :Name）的 IRI 解析为完整 URI，
        //   以便与 extractSubjectIri 解析 TTL 得到的完整 URI 进行正确比较。
        //   否则 DB 前缀格式与 TTL 完整 URI 不匹配会导致属性段被误删！
        java.util.function.Function<String, String> resolveDbIri = (dbIri) -> {
            if (dbIri == null || dbIri.isBlank()) return dbIri;
            // 去除 LLM 可能添加的尖括号 <:Name>
            String cleaned = dbIri.replaceAll("<\\s*(:[A-Za-z_][\\w\\-]*)\\s*>", ":$1");
            // 如果已是完整 URI，直接返回
            if (cleaned.startsWith("http://") || cleaned.startsWith("https://")) return cleaned;
            // 匹配前缀名格式 prefix:localname
            java.util.regex.Matcher pm = java.util.regex.Pattern.compile("^([A-Za-z_]\\w*)?:([A-Za-z_]\\w*)$").matcher(cleaned);
            if (pm.matches()) {
                String pfx = pm.group(1);
                if (pfx == null) pfx = "";
                String local = pm.group(2);
                String ns = resolvedPrefixMap.get(pfx);
                if (ns != null) return ns + local;
            }
            return cleaned;
        };

        // 从数据库中构建当前状态的 IRI 集合（标准化为完整 URI）
        java.util.Set<String> dbClassIris = new java.util.LinkedHashSet<>();
        java.util.Map<String, ObjectType> dbClassMap = new java.util.LinkedHashMap<>();
        for (ObjectType obj : objectTypes) {
            if (obj.getIri() != null && !obj.getIri().isBlank()) {
                String normalized = resolveDbIri.apply(obj.getIri());
                dbClassIris.add(normalized);
                dbClassMap.put(normalized, obj);
            }
        }
        java.util.Set<String> dbObjPropIris = new java.util.LinkedHashSet<>();
        // ★ 支持同一 IRI 多个 LinkType（owl:unionOf 展开后 domain/range 不同）
        java.util.Map<String, java.util.List<LinkType>> dbObjPropMap = new java.util.LinkedHashMap<>();
        java.util.Set<String> dbLinkDtPropIris = new java.util.LinkedHashSet<>(); // LinkType 中的数据属性
        java.util.Map<String, LinkType> dbLinkDtPropMap = new java.util.LinkedHashMap<>();
        for (LinkType link : linkTypes) {
            if (link.getIri() == null || link.getIri().isBlank()) continue;
            String normalized = resolveDbIri.apply(link.getIri());
            if (isXsdDatatypeRange(link.getTargetType())) {
                dbLinkDtPropIris.add(normalized);
                dbLinkDtPropMap.put(normalized, link);
            } else {
                dbObjPropIris.add(normalized);
                dbObjPropMap.computeIfAbsent(normalized, k -> new java.util.ArrayList<>()).add(link);
            }
        }

        // 从原始 TTL 文本中识别已存在的 DatatypeProperty（从数据库中仅收集 propertiesJson 中的）
        java.util.Set<String> dbDataPropIris = new java.util.LinkedHashSet<>();
        java.util.Map<String, java.util.Map<String, String>> dbDataPropMap = new java.util.LinkedHashMap<>();
        for (ObjectType obj : objectTypes) {
            if (obj.getPropertiesJson() != null && !obj.getPropertiesJson().isBlank()) {
                try {
                    java.util.List<java.util.Map<String, String>> dataProps =
                            objectMapper.readValue(obj.getPropertiesJson(), java.util.List.class);
                    for (java.util.Map<String, String> prop : dataProps) {
                        String propIri = prop.getOrDefault("iri", "");
                        if (!propIri.isBlank()) {
                            String normalized = resolveDbIri.apply(propIri);
                            dbDataPropIris.add(normalized);
                            dbDataPropMap.put(normalized, prop);
                        }
                    }
                } catch (Exception ignored) {}
            }
        }

        // ===== 解析原始 TTL 文本中的实体及其所在行 =====
        java.util.Map<String, Integer> originalClassLines = new java.util.LinkedHashMap<>();  // IRI -> 起始行号
        java.util.Map<String, Integer> originalObjPropLines = new java.util.LinkedHashMap<>();
        java.util.Map<String, Integer> originalDataPropLines = new java.util.LinkedHashMap<>();
        int classSectionStart = -1, objPropSectionStart = -1, dataPropSectionStart = -1;

        for (int i = 0; i < originalLines.size(); i++) {
            String line = originalLines.get(i).trim();
            // 匹配类定义：显式 owl:Class 或 rdfs:subClassOf（LLM 紧凑格式如 :X rdfs:subClassOf :Y .）
            if ((line.contains("owl:Class") || line.contains("rdfs:subClassOf")) && !line.startsWith("#") && !line.startsWith("@prefix")) {
                // 从这行提取 IRI
                String iri = extractSubjectIri(line, prefixMap);
                if (iri != null) {
                    originalClassLines.put(iri, i);
                }
            }
            if (line.contains("owl:ObjectProperty") && !line.startsWith("#") && !line.startsWith("@prefix")) {
                String iri = extractSubjectIri(line, prefixMap);
                if (iri != null) {
                    originalObjPropLines.put(iri, i);
                }
            }
            if (line.contains("owl:DatatypeProperty") && !line.startsWith("#") && !line.startsWith("@prefix")) {
                String iri = extractSubjectIri(line, prefixMap);
                if (iri != null) {
                    originalDataPropLines.put(iri, i);
                }
            }
            // 识别区块标记（兼容中英文格式）
            if (line.contains("# ===== 类定义 =====") || (line.equals("# Classes") && !line.contains("owl:Class"))) {
                classSectionStart = i;
            }
            if (line.contains("# ===== 对象属性") || line.equals("# Object Properties")) {
                objPropSectionStart = i;
            }
            if (line.contains("# ===== 数据属性") || line.equals("# Data Properties")) {
                dataPropSectionStart = i;
            }
        }

        // ★ 第二遍扫描：处理多行格式的 TTL（IRI 在第一行，a owl:ObjectProperty 等类型标记在后续行）
        for (int i = 0; i < originalLines.size(); i++) {
            String line = originalLines.get(i).trim();
            if (line.isEmpty() || line.startsWith("#") || line.startsWith("@prefix")) continue;
            String iri = extractSubjectIri(line, prefixMap);
            if (iri == null) continue;
            if (originalClassLines.containsKey(iri) || originalObjPropLines.containsKey(iri)
                    || originalDataPropLines.containsKey(iri)) continue;
            for (int j = i + 1; j < Math.min(i + 4, originalLines.size()); j++) {
                String nextLine = originalLines.get(j).trim();
                if (nextLine.isEmpty()) continue;
                if (nextLine.startsWith("#") || nextLine.startsWith("@prefix")) continue;
                if (nextLine.contains("owl:Class") || nextLine.contains("rdfs:subClassOf")) {
                    originalClassLines.put(iri, i);
                    break;
                }
                if (nextLine.contains("owl:ObjectProperty")) {
                    originalObjPropLines.put(iri, i);
                    break;
                }
                if (nextLine.contains("owl:DatatypeProperty")) {
                    originalDataPropLines.put(iri, i);
                    break;
                }
                if (nextLine.endsWith(".")) break;
            }
        }

        // ===== 增量合并 =====
        // 将一个三元组块（以 "." 结尾）序列化为 TTL 行
        // 策略：逐行处理原始 TTL，对每个实体块判断是否需要更新、删除或保留

        // 收集需要新增的类/属性（在数据库中存在但原始 TTL 中不存在）
        java.util.Set<String> newClassIris = new java.util.LinkedHashSet<>(dbClassIris);
        newClassIris.removeAll(originalClassLines.keySet());

        java.util.Set<String> newObjPropIris = new java.util.LinkedHashSet<>(dbObjPropIris);
        newObjPropIris.removeAll(originalObjPropLines.keySet());

        java.util.Set<String> newDataPropIris = new java.util.LinkedHashSet<>(dbDataPropIris);
        newDataPropIris.addAll(dbLinkDtPropIris); // 包含 LinkType 中 range 为 XSD 的数据属性
        newDataPropIris.removeAll(originalDataPropLines.keySet());

        // 需要删除的（原始 TTL 中存在但数据库中不存在）
        java.util.Set<String> removedClassIris = new java.util.LinkedHashSet<>(originalClassLines.keySet());
        removedClassIris.removeAll(dbClassIris);

        java.util.Set<String> removedObjPropIris = new java.util.LinkedHashSet<>(originalObjPropLines.keySet());
        removedObjPropIris.removeAll(dbObjPropIris);
        // 如果 datatype property 的 IRI 原本错误地出现在 ObjectProperty 区块，
        // 将其从 objProp 中移除并归入 dataProp 处理
        for (String iri : dbLinkDtPropIris) {
            if (originalObjPropLines.containsKey(iri)) {
                removedObjPropIris.add(iri);
            }
        }

        java.util.Set<String> removedDataPropIris = new java.util.LinkedHashSet<>(originalDataPropLines.keySet());
        removedDataPropIris.removeAll(dbDataPropIris);
        removedDataPropIris.removeAll(dbLinkDtPropIris); // 已在 LinkType 中保留的数据属性不应删除

        // 构建要删除的行号集合（整个三元组块）
        java.util.Set<Integer> linesToRemove = new java.util.HashSet<>();
        for (String iri : removedClassIris) {
            int startLine = originalClassLines.get(iri);
            for (int j = startLine; j < originalLines.size(); j++) {
                linesToRemove.add(j);
                if (originalLines.get(j).trim().endsWith(".")) break;
            }
        }
        for (String iri : removedObjPropIris) {
            int startLine = originalObjPropLines.get(iri);
            for (int j = startLine; j < originalLines.size(); j++) {
                linesToRemove.add(j);
                if (originalLines.get(j).trim().endsWith(".")) break;
            }
        }
        for (String iri : removedDataPropIris) {
            int startLine = originalDataPropLines.get(iri);
            for (int j = startLine; j < originalLines.size(); j++) {
                linesToRemove.add(j);
                if (originalLines.get(j).trim().endsWith(".")) break;
            }
        }

        // 需要更新的类/属性（IRI 存在于原始 TTL 和数据库中，内容可能有变化）
        java.util.Map<Integer, String> lineUpdates = new java.util.LinkedHashMap<>();  // 行号 -> 新内容
        for (java.util.Map.Entry<String, ObjectType> entry : dbClassMap.entrySet()) {
            String iri = entry.getKey();
            if (originalClassLines.containsKey(iri)) {
                int startLine = originalClassLines.get(iri);
                String originalBlock = extractTtlBlock(originalLines, startLine);
                ObjectType obj = entry.getValue();
                // 比较关键字段，若无变化则保留原始块
                if (nullSafeEquals(extractTtlLiteral(originalBlock, "rdfs:label"), obj.getLabel())
                        && nullSafeEquals(extractTtlLiteral(originalBlock, "rdfs:comment"), obj.getDescription())
                        && nullSafeEquals(extractTtlIriRef(originalBlock, "rdfs:subClassOf"),
                                obj.getParentIri() != null ? shortenIri(obj.getParentIri(), prefixMap) : null)) {
                    continue; // 值未变，保留原始格式
                }
                String newBlock = buildClassBlock(obj, prefixMap);
                if (newBlock != null) {
                    // 找到块的结束行
                    int endLine = startLine;
                    for (int j = startLine; j < originalLines.size(); j++) {
                        endLine = j;
                        if (originalLines.get(j).trim().endsWith(".")) break;
                    }
                    // 标记要替换的行
                    for (int j = startLine; j <= endLine; j++) {
                        lineUpdates.put(j, null); // 标记为删除
                    }
                    lineUpdates.put(startLine, newBlock); // 标记为插入新内容
                }
            }
        }
        for (java.util.Map.Entry<String, java.util.List<LinkType>> entry : dbObjPropMap.entrySet()) {
            String iri = entry.getKey();
            if (originalObjPropLines.containsKey(iri)) {
                int startLine = originalObjPropLines.get(iri);
                String originalBlock = extractTtlBlock(originalLines, startLine);
                java.util.List<LinkType> links = entry.getValue();
                // 合并多条同 IRI 记录为一条带 owl:unionOf 的声明
                String newBlock = buildObjPropBlockMerged(links, prefixMap);
                if (newBlock != null) {
                    int endLine = startLine;
                    for (int j = startLine; j < originalLines.size(); j++) {
                        endLine = j;
                        if (originalLines.get(j).trim().endsWith(".")) break;
                    }
                    for (int j = startLine; j <= endLine; j++) {
                        lineUpdates.put(j, null);
                    }
                    lineUpdates.put(startLine, newBlock);
                }
            }
        }
        // DatatypeProperty 更新（来自 propertiesJson）
        for (java.util.Map.Entry<String, java.util.Map<String, String>> entry : dbDataPropMap.entrySet()) {
            String iri = entry.getKey();
            if (originalDataPropLines.containsKey(iri)) {
                int startLine = originalDataPropLines.get(iri);
                String originalBlock = extractTtlBlock(originalLines, startLine);
                java.util.Map<String, String> prop = entry.getValue();
                // 比较关键字段，若无变化则保留原始块
                if (nullSafeEquals(extractTtlLiteral(originalBlock, "rdfs:label"), prop.get("label"))
                        && nullSafeEquals(extractTtlLiteral(originalBlock, "rdfs:comment"), prop.get("description"))
                        && nullSafeEquals(extractTtlIriRef(originalBlock, "rdfs:domain"),
                                prop.get("domain") != null ? shortenIri(prop.get("domain"), prefixMap) : null)) {
                    continue; // 值未变，保留原始格式
                }
                String newBlock = buildDataPropBlock(prop, prefixMap);
                if (newBlock != null) {
                    int endLine = startLine;
                    for (int j = startLine; j < originalLines.size(); j++) {
                        endLine = j;
                        if (originalLines.get(j).trim().endsWith(".")) break;
                    }
                    for (int j = startLine; j <= endLine; j++) {
                        lineUpdates.put(j, null);
                    }
                    lineUpdates.put(startLine, newBlock);
                }
            }
        }
        // DatatypeProperty 更新（来自 LinkType，range 为 XSD 类型）
        for (java.util.Map.Entry<String, LinkType> entry : dbLinkDtPropMap.entrySet()) {
            String iri = entry.getKey();
            if (originalDataPropLines.containsKey(iri)) {
                int startLine = originalDataPropLines.get(iri);
                String originalBlock = extractTtlBlock(originalLines, startLine);
                LinkType link = entry.getValue();
                // 比较关键字段，若无变化则保留原始块
                if (nullSafeEquals(extractTtlLiteral(originalBlock, "rdfs:label"), link.getLabel())
                        && nullSafeEquals(extractTtlLiteral(originalBlock, "rdfs:comment"), link.getDescription())
                        && nullSafeEquals(extractTtlIriRef(originalBlock, "rdfs:domain"),
                                link.getSourceType() != null ? shortenIri(link.getSourceType(), prefixMap) : null)) {
                    continue; // 值未变，保留原始格式
                }
                String newBlock = buildDataPropBlockFromLinkType(link, prefixMap);
                if (newBlock != null) {
                    int endLine = startLine;
                    for (int j = startLine; j < originalLines.size(); j++) {
                        endLine = j;
                        if (originalLines.get(j).trim().endsWith(".")) break;
                    }
                    for (int j = startLine; j <= endLine; j++) {
                        lineUpdates.put(j, null);
                    }
                    lineUpdates.put(startLine, newBlock);
                }
            }
        }

        // ===== 生成最终 TTL =====
        StringBuilder result = new StringBuilder();
        // 合并所有要跳过的行
        java.util.Set<Integer> skipLines = new java.util.HashSet<>(linesToRemove);
        for (java.util.Map.Entry<Integer, String> entry : lineUpdates.entrySet()) {
            if (entry.getValue() == null) skipLines.add(entry.getKey());
        }

        for (int i = 0; i < originalLines.size(); i++) {
            if (skipLines.contains(i)) {
                // 检查是否是有替换内容的行
                if (lineUpdates.containsKey(i) && lineUpdates.get(i) != null) {
                    result.append(lineUpdates.get(i));
                }
                continue;
            }
            result.append(originalLines.get(i)).append("\n");

            // 在对象属性区块标记前追加新类
            if (!newClassIris.isEmpty() && objPropSectionStart >= 0 && i == objPropSectionStart - 1) {
                for (String iri : newClassIris) {
                    result.append(buildClassBlock(dbClassMap.get(iri), prefixMap));
                }
            }
            // 在数据属性区块标记前追加新对象属性
            if (!newObjPropIris.isEmpty() && dataPropSectionStart >= 0 && i == dataPropSectionStart - 1) {
                for (String iri : newObjPropIris) {
                    java.util.List<LinkType> links = dbObjPropMap.get(iri);
                    if (links != null && !links.isEmpty()) {
                        result.append(buildObjPropBlockMerged(links, prefixMap));
                    }
                }
            }
            // 在文件末尾追加新增内容（当没有区块标记时）
            if (i == originalLines.size() - 1) {
                // 没有对象属性区块标记时，新类追加到文件末尾
                if (!newClassIris.isEmpty() && objPropSectionStart < 0) {
                    for (String iri : newClassIris) {
                        result.append(buildClassBlock(dbClassMap.get(iri), prefixMap));
                    }
                }
                // 没有数据属性区块标记时，新对象属性追加到文件末尾
                if (!newObjPropIris.isEmpty() && dataPropSectionStart < 0) {
                    for (String iri : newObjPropIris) {
                        java.util.List<LinkType> links = dbObjPropMap.get(iri);
                        if (links != null && !links.isEmpty()) {
                            result.append(buildObjPropBlockMerged(links, prefixMap));
                        }
                    }
                }
                // 新数据属性追加到文件末尾
                if (!newDataPropIris.isEmpty()) {
                    if (dataPropSectionStart < 0) {
                        result.append("\n# ===== 数据属性 =====\n");
                    }
                    for (String iri : newDataPropIris) {
                        // 优先从 LinkType 构建（range 为 XSD 的数据属性），否则从 propertiesJson 构建
                        LinkType lt = dbLinkDtPropMap.get(iri);
                        if (lt != null) {
                            result.append(buildDataPropBlockFromLinkType(lt, prefixMap));
                        } else {
                            java.util.Map<String, String> dp = dbDataPropMap.get(iri);
                            if (dp != null) {
                                result.append(buildDataPropBlock(dp, prefixMap));
                            }
                        }
                    }
                }
            }
        }

        return result.toString();
    }

    /**
     * 从 TTL 行中提取主语 IRI（使用前缀映射还原为完整 IRI）
     */
    private String extractSubjectIri(String line, java.util.Map<String, String> prefixMap) {
        String trimmed = line.trim();
        if (trimmed.isEmpty() || trimmed.startsWith("#") || trimmed.startsWith("@prefix")) return null;
        // 提取主语（冒号前缀形式，如 :Customer 或 prop:hasName）
        String subject;
        if (trimmed.startsWith("<") && trimmed.contains(">")) {
            subject = trimmed.substring(1, trimmed.indexOf('>'));
        } else {
            int spaceIdx = trimmed.indexOf(' ');
            if (spaceIdx <= 0) return null;
            subject = trimmed.substring(0, spaceIdx);
        }
        // 还原前缀为完整 IRI
        if (subject.startsWith("<") && subject.endsWith(">")) {
            return subject.substring(1, subject.length() - 1);
        }
        int colonIdx = subject.indexOf(':');
        if (colonIdx >= 0) {
            String prefix = colonIdx > 0 ? subject.substring(0, colonIdx) : "";
            String local = subject.substring(colonIdx + 1);
            String ns = prefixMap.get(prefix);
            // 空前缀 "" 对应 default namespace
            if (ns == null) {
                // 尝试不带冒号的前缀（即默认命名空间）
                ns = prefixMap.get("");
                if (ns != null) {
                    return ns + local;
                }
                return null;
            }
            return ns + local;
        }
        return null;
    }

    /** 从 Jena Model 中提取前缀映射 */
    private java.util.Map<String, String> extractPrefixesFromModel(Model model) {
        java.util.Map<String, String> prefixMap = new java.util.LinkedHashMap<>();
        // 预置常用前缀
        prefixMap.put("owl", "http://www.w3.org/2002/07/owl#");
        prefixMap.put("rdf", "http://www.w3.org/1999/02/22-rdf-syntax-ns#");
        prefixMap.put("rdfs", "http://www.w3.org/2000/01/rdf-schema#");
        prefixMap.put("xsd", "http://www.w3.org/2001/XMLSchema#");
        try {
            model.lock();
            java.util.Iterator<String> prefixes = model.listNameSpaces();
            while (prefixes.hasNext()) {
                String ns = prefixes.next();
                String prefix = model.getNsURIPrefix(ns);
                if (prefix != null && !prefixMap.containsKey(prefix)) {
                    prefixMap.put(prefix, ns);
                }
            }
        } catch (Exception ignored) {}
        return prefixMap;
    }

    /** 从 TTL 行中提取主语（第一个 token，如 ":Customer"、">prop:hasName" 或 ">IRI>"） */
    private String extractSubject(String trimmedLine) {
        trimmedLine = trimmedLine.trim();
        int end = trimmedLine.length();
        // 处理 >IRI> 格式：提取 > 和 > 之间的内容（不含尖括号）
        if (trimmedLine.startsWith("<")) {
            int closeBracket = trimmedLine.indexOf('>');
            if (closeBracket > 1) {
                return trimmedLine.substring(1, closeBracket);
            }
            return "";
        }
        for (int i = 0; i < trimmedLine.length(); i++) {
            char c = trimmedLine.charAt(i);
            if (Character.isWhitespace(c) || c == ';') {
                end = i;
                break;
            }
        }
        return trimmedLine.substring(0, end);
    }

    /** 从 TTL @prefix : 声明中提取基础命名空间 IRI */
    private String extractBaseNamespace(String ttl) {
        for (String line : ttl.split("\n")) {
            String trimmed = line.trim();
            if (trimmed.startsWith("@prefix")) {
                // 仅匹配默认命名空间 @prefix : <IRI>，排除 @prefix owl: 等具名前缀
                String afterPrefix = trimmed.substring("@prefix".length()).trim();
                if (!afterPrefix.startsWith(":")) continue;
                int start = trimmed.indexOf('<');
                int end = trimmed.indexOf('>');
                if (start >= 0 && end > start) {
                    return trimmed.substring(start + 1, end);
                }
            }
        }
        return null;
    }

    /** 展开前缀缩写为完整 IRI */
    private String expandPrefix(String shortForm, java.util.Map<String, String> prefixMap) {
        if (shortForm == null) return null;
        if (shortForm.startsWith("<") && shortForm.endsWith(">")) {
            return shortForm.substring(1, shortForm.length() - 1);
        }
        int colonIdx = shortForm.indexOf(':');
        if (colonIdx >= 0) {
            String prefix = colonIdx > 0 ? shortForm.substring(0, colonIdx) : "";
            String local = shortForm.substring(colonIdx + 1);
            String ns = prefixMap.get(prefix);
            if (ns != null) return ns + local;
            // 如果显式前缀解析失败，尝试用 "" 默认命名空间再查一次
            if (!prefix.isEmpty()) {
                ns = prefixMap.get("");
                if (ns != null) return ns + local;
            }
        }
        return null;
    }

    /**
     * 判断一行以某个已知命名空间中的谓词开头（如 rdfs:subClassOf），而非以主语开头。
     * 用于文本回退解析器区分 LLM 紧凑格式的类声明和延续行。
     */
    private boolean isStartsWithKnownPredicate(String trimmedLine,
            java.util.Map<String, String> prefixMap, java.util.Set<String> knownNsIris) {
        String firstToken = extractSubject(trimmedLine);
        if (firstToken == null || firstToken.isBlank()) return true; // 无法识别，跳过
        String fullIri = expandPrefix(firstToken, prefixMap);
        if (fullIri == null) {
            fullIri = firstToken;
        }
        return isKnownNsTerm(fullIri, knownNsIris);
    }

    /** 判断一个 IRI 是否属于已知的 W3C 命名空间（如 rdf/rdfs/owl/xsd/swrl） */
    private boolean isKnownNsTerm(String iri, java.util.Set<String> knownNsIris) {
        if (iri == null || iri.isBlank()) return false;
        for (String ns : knownNsIris) {
            if (iri.startsWith(ns)) return true;
        }
        return false;
    }

    /** 从 TTL 块中提取指定属性的值（文本解析方式，用于 Jena 失败时的回退） */
    private String extractTextProperty(String[] lines, int startLine, String propertyName,
                                        java.util.Map<String, String> prefixMap) {
        for (int i = startLine; i < lines.length && i < startLine + 20; i++) {
            String trimmed = lines[i].trim();
            // ★ 先检查当前行是否包含目标属性（必须在 "ends with ." 判断之前，否则 rdfs:range 等作为
            //    块最后一行以 "." 结尾的属性会被跳过，导致 LinkType.targetType 始终为 null）
            if (trimmed.contains(propertyName)) {
                // 提取属性值，可能是缩写 IRI 或完整 IRI
                String value = trimmed.split(propertyName)[1].trim()
                        .replaceAll("[;,\\s\\.].*$", "").trim();
                if (value.startsWith("\"")) {
                    // 字面量值，提取引号内内容
                    int end = value.indexOf('"', 1);
                    return end > 0 ? value.substring(1, end) : value;
                }
                // ★ 展开前缀为完整 IRI（与 ObjectType.iri 保持一致，否则前端图谱匹配会失败）
                String expanded = expandPrefix(value, prefixMap);
                if (expanded != null) {
                    return expanded;
                }
                // 如果 expandPrefix 返回 null，不要直接返回短前缀形式（如 :Foo），
                // 否则与 Jena 解析的 ObjectType.iri（全 URI）不匹配，图谱边将全部丢失。
                // 兜底：尝试用 value 的原始文本匹配基础命名空间
                if (value.startsWith(":") && value.indexOf(':', 1) < 0) {
                    String baseNs = prefixMap.get("");
                    if (baseNs != null) {
                        return baseNs + value.substring(1);
                    }
                }
                // 最终回退：返回原始值（可能是完整 URI <...> 形式）
                return value;
            }
            if (trimmed.endsWith(".")) break; // 块结束
        }
        return null;
    }

    /**
     * 构建类定义的 TTL 块
     */
    private String buildClassBlock(ObjectType obj, java.util.Map<String, String> prefixMap) {
        if (obj.getIri() == null || obj.getIri().isBlank()) return "";
        String shortIri = shortenIri(obj.getIri(), prefixMap);
        StringBuilder sb = new StringBuilder();
        sb.append(shortIri).append(" a owl:Class ;\n");
        sb.append("    rdfs:label \"").append(escapeTtlString(obj.getLabel() != null ? obj.getLabel() : "")).append("\"");
        if (obj.getDescription() != null && !obj.getDescription().isBlank()) {
            sb.append(" ;\n    rdfs:comment \"").append(escapeTtlString(obj.getDescription())).append("\"");
        }
        if (obj.getParentIri() != null && !obj.getParentIri().isBlank()) {
            sb.append(" ;\n    rdfs:subClassOf ").append(shortenIri(obj.getParentIri(), prefixMap));
        }
        sb.append(" .\n");
        return sb.toString();
    }

    /**
     * 构建对象属性的 TTL 块
     */
    private String buildObjPropBlock(LinkType link, java.util.Map<String, String> prefixMap) {
        if (link.getIri() == null || link.getIri().isBlank()) return "";
        String shortIri = shortenIri(link.getIri(), prefixMap);
        StringBuilder sb = new StringBuilder();
        sb.append(shortIri).append(" a owl:ObjectProperty ;\n");
        sb.append("    rdfs:label \"").append(escapeTtlString(link.getLabel() != null ? link.getLabel() : "")).append("\"");
        if (link.getDescription() != null && !link.getDescription().isBlank()) {
            sb.append(" ;\n    rdfs:comment \"").append(escapeTtlString(link.getDescription())).append("\"");
        }
        if (link.getSourceType() != null && !link.getSourceType().isBlank()) {
            sb.append(" ;\n    rdfs:domain ").append(shortenIri(link.getSourceType(), prefixMap));
        }
        if (link.getTargetType() != null && !link.getTargetType().isBlank()) {
            sb.append(" ;\n    rdfs:range ").append(shortenIri(link.getTargetType(), prefixMap));
        }
        sb.append(" .\n");
        return sb.toString();
    }

    /**
     * 合并相同 IRI 的多个 LinkType 为单个 owl:ObjectProperty 声明。
     * 当 LLM 返回 owl:unionOf domain/range 时，会被 expand 为多条 LinkType 记录，
     * 此方法将它们重新合并为带 owl:unionOf 的合法 TTL 声明。
     */
    private String buildObjPropBlockMerged(java.util.List<LinkType> links,
                                           java.util.Map<String, String> prefixMap) {
        if (links == null || links.isEmpty()) return "";
        LinkType first = links.get(0);
        String shortIri = shortenIri(first.getIri(), prefixMap);

        // 收集唯一的 domain 和 range
        java.util.Set<String> domains = new java.util.LinkedHashSet<>();
        java.util.Set<String> ranges = new java.util.LinkedHashSet<>();
        for (LinkType link : links) {
            if (link.getSourceType() != null && !link.getSourceType().isBlank()) {
                domains.add(link.getSourceType());
            }
            if (link.getTargetType() != null && !link.getTargetType().isBlank()) {
                ranges.add(link.getTargetType());
            }
        }

        StringBuilder sb = new StringBuilder();
        sb.append(shortIri).append(" a owl:ObjectProperty ;\n");
        sb.append("    rdfs:label \"").append(escapeTtlString(first.getLabel() != null ? first.getLabel() : "")).append("\"");
        if (first.getDescription() != null && !first.getDescription().isBlank()) {
            sb.append(" ;\n    rdfs:comment \"").append(escapeTtlString(first.getDescription())).append("\"");
        }

        // 序列化 domain
        if (!domains.isEmpty()) {
            sb.append(" ;\n    rdfs:domain ");
            sb.append(formatClassSet(domains, prefixMap));
        }

        // 序列化 range
        if (!ranges.isEmpty()) {
            sb.append(" ;\n    rdfs:range ");
            sb.append(formatClassSet(ranges, prefixMap));
        }

        sb.append(" .\n");
        return sb.toString();
    }

    /**
     * 将多个类 IRI 格式化为 TTL class expression。
     * - 1 个 → 直接返回 :ClassName
     * - 多个 → [ owl:unionOf (:A :B ...) ]
     */
    private String formatClassSet(java.util.Set<String> iris, java.util.Map<String, String> prefixMap) {
        if (iris.size() == 1) {
            return shortenIri(iris.iterator().next(), prefixMap);
        }
        StringBuilder sb = new StringBuilder("[ owl:unionOf (");
        for (String iri : iris) {
            sb.append(shortenIri(iri, prefixMap)).append(" ");
        }
        sb.append(") ]");
        return sb.toString();
    }

    /**
     * 构建数据属性的 TTL 块
     */
    private String buildDataPropBlock(java.util.Map<String, String> prop, java.util.Map<String, String> prefixMap) {
        String iri = prop.getOrDefault("iri", "");
        if (iri.isBlank()) return "";
        String shortIri = shortenIri(iri, prefixMap);
        StringBuilder sb = new StringBuilder();
        sb.append(shortIri).append(" a owl:DatatypeProperty ;\n");
        sb.append("    rdfs:label \"").append(escapeTtlString(prop.getOrDefault("label", ""))).append("\"");
        String description = prop.get("description");
        if (description != null && !description.isBlank()) {
            sb.append(" ;\n    rdfs:comment \"").append(escapeTtlString(description)).append("\"");
        }
        String domain = prop.get("domain");
        if (domain != null && !domain.isBlank()) {
            sb.append(" ;\n    rdfs:domain ").append(shortenIri(domain, prefixMap));
        }
        String range = prop.get("range");
        if (range != null && !range.isBlank()) {
            sb.append(" ;\n    rdfs:range ").append(formatRangeValue(range));
        } else {
            sb.append(" ;\n    rdfs:range xsd:string");
        }
        sb.append(" .\n");
        return sb.toString();
    }

    /**
     * 将 LinkType 记录作为 owl:DatatypeProperty 序列化（range 为 XSD 类型）
     */
    private String buildDataPropBlockFromLinkType(LinkType link, java.util.Map<String, String> prefixMap) {
        if (link.getIri() == null || link.getIri().isBlank()) return "";
        String shortIri = shortenIri(link.getIri(), prefixMap);
        StringBuilder sb = new StringBuilder();
        sb.append(shortIri).append(" a owl:DatatypeProperty ;\n");
        sb.append("    rdfs:label \"").append(escapeTtlString(link.getLabel() != null ? link.getLabel() : "")).append("\"");
        if (link.getDescription() != null && !link.getDescription().isBlank()) {
            sb.append(" ;\n    rdfs:comment \"").append(escapeTtlString(link.getDescription())).append("\"");
        }
        if (link.getSourceType() != null && !link.getSourceType().isBlank()) {
            sb.append(" ;\n    rdfs:domain ").append(shortenIri(link.getSourceType(), prefixMap));
        }
        if (link.getTargetType() != null && !link.getTargetType().isBlank()) {
            sb.append(" ;\n    rdfs:range ").append(formatRangeValue(link.getTargetType()));
        } else {
            sb.append(" ;\n    rdfs:range xsd:string");
        }
        sb.append(" .\n");
        return sb.toString();
    }

    /**
     * 判断 range URI 是否为 XSD 数据类型（用于区分 owl:DatatypeProperty 和 owl:ObjectProperty）。
     */
    private boolean isXsdDatatypeRange(String rangeUri) {
        if (rangeUri == null || rangeUri.isBlank()) return false;
        String u = rangeUri.trim();
        return u.startsWith("http://www.w3.org/2001/XMLSchema#")
                || u.startsWith("http://www.w3.org/2000/10/XMLSchema#")
                || u.startsWith("http://www.w3.org/1999/02/22-rdf-syntax-ns#")
                || u.equals("http://www.w3.org/2000/01/rdf-schema#Literal");
    }

    /**
     * 格式化 DatatypeProperty 的 range 值，确保 TTL 语法正确
     * - 已有尖括号的完整 URI 保持不变：<http://...>
     * - 无尖括号的完整 URI 自动补尖括号：http://... → <http://...>
     * - 前缀缩写形式保持不变：xsd:string
     */
    private String formatRangeValue(String range) {
        if (range == null || range.isBlank()) return "xsd:string";
        String trimmed = range.trim();
        if (trimmed.startsWith("<") && trimmed.endsWith(">")) return trimmed;
        if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) {
            return "<" + trimmed + ">";
        }
        return trimmed;
    }

    /**
     * 将完整 IRI 转换为前缀缩写形式
     */
    private String shortenIri(String fullIri, java.util.Map<String, String> prefixMap) {
        if (fullIri == null || fullIri.isBlank()) return ":Unknown";
        // 如果已经是合法的前缀名形式（如 :Name 或 prefix:Name），直接返回
        if (fullIri.matches("^[A-Za-z_]\\w*:[A-Za-z_]\\w*$") || fullIri.matches("^:[A-Za-z_]\\w*$")) {
            return fullIri;
        }
        // 去除 LLM 可能添加的尖括号包装 <:Name> → :Name
        String cleaned = fullIri.replaceAll("<\\s*(:[A-Za-z_][\\w\\-]*)\\s*>", ":$1");
        if (!cleaned.equals(fullIri)) {
            // 去除尖括号后若已是合法前缀名，直接返回
            if (cleaned.matches("^[A-Za-z_]\\w*:[A-Za-z_]\\w*$") || cleaned.matches("^:[A-Za-z_]\\w*$")) {
                return cleaned;
            }
            fullIri = cleaned;
        }
        // 查找匹配的前缀
        for (java.util.Map.Entry<String, String> entry : prefixMap.entrySet()) {
            String prefix = entry.getKey();
            String ns = entry.getValue();
            if (fullIri.startsWith(ns)) {
                String local = fullIri.substring(ns.length());
                if (prefix.isEmpty()) {
                    return ":" + local;
                }
                return prefix + ":" + local;
            }
        }
        // 最终兜底：如果已经是简单名称（无特殊字符），添加默认前缀
        if (fullIri.matches("^[A-Za-z_]\\w*$")) {
            return ":" + fullIri;
        }
        return "<" + fullIri + ">";
    }

    /**
     * 当 Jena 解析 TTL 失败时，手动从 @prefix 行提取前缀映射
     */
    private java.util.Map<String, String> extractPrefixesManually(List<String> lines) {
        java.util.Map<String, String> prefixes = new java.util.LinkedHashMap<>();
        for (String line : lines) {
            String trimmed = line.trim();
            if (trimmed.startsWith("@prefix")) {
                try {
                    // @prefix xsd: <http://...> .
                    String content = trimmed.substring("@prefix".length()).trim();
                    if (content.endsWith(".")) content = content.substring(0, content.length() - 1).trim();
                    int colonIdx = content.indexOf(':');
                    if (colonIdx >= 0) {
                        String prefix = colonIdx > 0 ? content.substring(0, colonIdx).trim() : "";
                        String rest = content.substring(colonIdx + 1).trim();
                        if (rest.startsWith("<") && rest.endsWith(">")) {
                            prefixes.put(prefix, rest.substring(1, rest.length() - 1));
                        }
                    }
                } catch (Exception ignored) {}
            }
        }
        return prefixes;
    }

    /**
     * 全新生成 TTL（无原始文件时使用）
     */
    private String buildTtlFromScratch(Ontology ontology, List<ObjectType> objectTypes, List<LinkType> linkTypes) {
        // 从现有数据中动态推断前缀
        String classPrefix = "http://ontologybuilder.local/ontology#";
        for (ObjectType obj : objectTypes) {
            if (obj.getIri() != null && obj.getIri().contains("#")) {
                classPrefix = obj.getIri().substring(0, obj.getIri().lastIndexOf('#') + 1);
                break;
            }
        }

        StringBuilder ttl = new StringBuilder();
        ttl.append("@prefix : <").append(classPrefix).append("> .\n");
        ttl.append("@prefix rdf: <http://www.w3.org/1999/02/22-rdf-syntax-ns#> .\n");
        ttl.append("@prefix rdfs: <http://www.w3.org/2000/01/rdf-schema#> .\n");
        ttl.append("@prefix owl: <http://www.w3.org/2002/07/owl#> .\n");
        ttl.append("@prefix xsd: <http://www.w3.org/2001/XMLSchema#> .\n");
        ttl.append("@prefix swrl: <http://www.w3.org/2003/11/swrl#> .\n");
        ttl.append("@prefix swrlb: <http://www.w3.org/2003/11/swrlb#> .\n");
        ttl.append("@prefix var: <http://www.example.org/variable#> .\n\n");
        ttl.append(":Ontology a owl:Ontology ;\n");
        ttl.append("    rdfs:label \"").append(ontology.getName() != null ? ontology.getName() : "My Ontology").append("\" ;\n");
        ttl.append("    owl:versionInfo \"v").append(ontology.getVersion() != null ? ontology.getVersion() : "1.0.0").append("\" .\n\n");

        java.util.Map<String, String> prefixMap = new java.util.LinkedHashMap<>();
        prefixMap.put("", classPrefix);
        prefixMap.put("rdf", "http://www.w3.org/1999/02/22-rdf-syntax-ns#");
        prefixMap.put("rdfs", "http://www.w3.org/2000/01/rdf-schema#");
        prefixMap.put("owl", "http://www.w3.org/2002/07/owl#");
        prefixMap.put("xsd", "http://www.w3.org/2001/XMLSchema#");

        ttl.append("# ===== 类定义 =====\n");
        for (ObjectType obj : objectTypes) {
            ttl.append(buildClassBlock(obj, prefixMap));
        }

        // 分离对象属性和数据属性：range 为 XSD 数据类型的 LinkType 应输出为 owl:DatatypeProperty
        java.util.List<LinkType> objProps = new java.util.ArrayList<>();
        java.util.List<LinkType> dtProps = new java.util.ArrayList<>();
        for (LinkType link : linkTypes) {
            if (isXsdDatatypeRange(link.getTargetType())) {
                dtProps.add(link);
            } else {
                objProps.add(link);
            }
        }

        ttl.append("\n# ===== 对象属性（类之间的关系） =====\n");
        // ★ 合并相同 IRI 的 LinkType（展开 owl:unionOf 后可能产生多个记录），
        //    将同一属性的多个 domain/range 合并为 owl:unionOf 表达式
        java.util.Map<String, java.util.List<LinkType>> linksByIri = new java.util.LinkedHashMap<>();
        for (LinkType link : objProps) {
            String key = link.getIri() != null ? link.getIri().trim() : "";
            if (key.isBlank()) continue;
            linksByIri.computeIfAbsent(key, k -> new java.util.ArrayList<>()).add(link);
        }
        for (java.util.List<LinkType> links : linksByIri.values()) {
            ttl.append(buildObjPropBlockMerged(links, prefixMap));
        }

        // 数据属性：合并 LinkType 中的 DatatypeProperty 和 propertiesJson
        java.util.Set<String> writtenDataPropIris = new java.util.HashSet<>();
        StringBuilder dataPropsSection = new StringBuilder();

        // 先写 LinkType 中的 DatatypeProperty
        for (LinkType link : dtProps) {
            String iri = link.getIri();
            if (iri != null && !iri.isBlank()) {
                dataPropsSection.append(buildDataPropBlockFromLinkType(link, prefixMap));
                writtenDataPropIris.add(iri.trim());
            }
        }

        // 再写 propertiesJson 中的 DatatypeProperty（跳过已从 LinkType 写入的）
        for (ObjectType obj : objectTypes) {
            if (obj.getPropertiesJson() != null && !obj.getPropertiesJson().isBlank()) {
                try {
                    java.util.List<java.util.Map<String, String>> dataProps =
                            objectMapper.readValue(obj.getPropertiesJson(), java.util.List.class);
                    for (java.util.Map<String, String> prop : dataProps) {
                        String iri = prop.getOrDefault("iri", "");
                        if (!iri.isBlank() && !writtenDataPropIris.contains(iri.trim())) {
                            dataPropsSection.append(buildDataPropBlock(prop, prefixMap));
                            writtenDataPropIris.add(iri.trim());
                        }
                    }
                } catch (Exception ignored) {}
            }
        }
        if (dataPropsSection.length() > 0) {
            ttl.append("\n# ===== 数据属性 =====\n");
            ttl.append(dataPropsSection);
        }

        return ttl.toString();
    }
}
