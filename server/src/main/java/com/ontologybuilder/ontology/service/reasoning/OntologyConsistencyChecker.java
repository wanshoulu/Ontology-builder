package com.ontologybuilder.ontology.service.reasoning;

import com.ontologybuilder.common.exception.BusinessException;
import com.ontologybuilder.ontology.model.Ontology;
import com.ontologybuilder.ontology.model.ObjectType;
import com.ontologybuilder.ontology.model.LinkType;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.*;

/**
 * 本体一致性检查器
 * 检查本体的逻辑一致性和结构完整性
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OntologyConsistencyChecker {

    private final ReasoningService reasoningService;

    /**
     * 检查本体一致性
     */
    public ConsistencyCheckResult checkOntologyConsistency(
            Ontology ontology,
            List<ObjectType> objectTypes,
            List<LinkType> linkTypes) {

        try {
            log.info("检查本体一致性: {} ({})", ontology.getName(), ontology.getId());

            List<String> errors = new ArrayList<>();
            List<String> warnings = new ArrayList<>();

            // 分层检查结果
            List<CheckLayerResult> layers = new ArrayList<>();

            // 1. 基础结构检查
            CheckLayerResult layer1 = new CheckLayerResult();
            layer1.setName("基础结构检查");
            layer1.setDescription("检查本体名称、对象类型和关联类型的标签/IRI完整性");
            checkBasicStructure(ontology, objectTypes, linkTypes, layer1.getErrors(), layer1.getWarnings());
            // 收集详细通过的检查项
            collectBasicStructureDetails(ontology, objectTypes, linkTypes, layer1);
            layer1.setPassed(layer1.getErrors().isEmpty());
            layers.add(layer1);

            // 2. 逻辑一致性检查
            CheckLayerResult layer2 = new CheckLayerResult();
            layer2.setName("逻辑一致性检查");
            layer2.setDescription("检查关联类型的定义域/值域有效性、父类关系引用完整性");
            checkLogicalConsistency(ontology, objectTypes, linkTypes, layer2.getErrors(), layer2.getWarnings());
            collectLogicalConsistencyDetails(objectTypes, linkTypes, layer2);
            layer2.setPassed(layer2.getErrors().isEmpty());
            layers.add(layer2);

            // 3. 使用HermiT进行 OWL 2 DL 语义推理检查
            CheckLayerResult layer3 = new CheckLayerResult();
            layer3.setName("HermiT语义推理检查");
            layer3.setDescription("使用 HermiT 推理器执行 OWL 2 DL 完整语义推理验证");
            checkWithHermiTReasoning(ontology, objectTypes, linkTypes, layer3.getErrors(), layer3.getWarnings(), layer3);
            layer3.setPassed(layer3.getErrors().isEmpty());
            layers.add(layer3);

            // 4. 检查IRI唯一性
            CheckLayerResult layer4 = new CheckLayerResult();
            layer4.setName("IRI唯一性检查");
            layer4.setDescription("检查对象类型和关联类型的IRI是否唯一、是否存在跨类型冲突");
            checkIriUniqueness(objectTypes, linkTypes, layer4.getErrors(), layer4.getWarnings());
            collectIriUniquenessDetails(objectTypes, linkTypes, layer4);
            layer4.setPassed(layer4.getErrors().isEmpty());
            layers.add(layer4);

            // 5. 检查循环继承
            CheckLayerResult layer5 = new CheckLayerResult();
            layer5.setName("循环继承检查");
            layer5.setDescription("检测对象类型之间的循环继承依赖");
            checkCircularInheritance(objectTypes, layer5.getErrors(), layer5.getWarnings());
            collectCircularInheritanceDetails(objectTypes, layer5);
            layer5.setPassed(layer5.getErrors().isEmpty());
            layers.add(layer5);

            // 合并所有错误和警告
            for (CheckLayerResult layer : layers) {
                errors.addAll(layer.getErrors());
                warnings.addAll(layer.getWarnings());
            }

            boolean valid = errors.isEmpty();

            log.info("本体一致性检查完成: {}, valid={}, errors={}, warnings={}",
                    ontology.getName(), valid, errors.size(), warnings.size());

            return ConsistencyCheckResult.builder()
                    .ontologyId(ontology.getId())
                    .ontologyName(ontology.getName())
                    .valid(valid)
                    .errors(errors)
                    .warnings(warnings)
                    .classCount(objectTypes.size())
                    .propertyCount(linkTypes.size())
                    .layers(layers)
                    .build();

        } catch (Exception e) {
            log.error("本体一致性检查失败: {}", ontology.getName(), e);
            throw new BusinessException("本体一致性检查失败: " + e.getMessage());
        }
    }

    /**
     * 基础结构检查
     */
    private void checkBasicStructure(
            Ontology ontology,
            List<ObjectType> objectTypes,
            List<LinkType> linkTypes,
            List<String> errors,
            List<String> warnings) {

        // 检查本体基本信息
        if (ontology.getName() == null || ontology.getName().trim().isEmpty()) {
            errors.add("本体缺少名称");
        }

        // 检查对象类型
        if (objectTypes.isEmpty()) {
            warnings.add("本体没有定义任何对象类型（类） → 修复建议: 在对象类型管理中添加至少一个类（ObjectType），定义本体的核心概念");
        }

        // 检查关联类型
        if (linkTypes.isEmpty()) {
            warnings.add("本体没有定义任何关联类型（属性） → 修复建议: 在关联类型管理中添加至少一个属性（LinkType），定义类之间的关系");
        }

        // 检查对象类型的完整性
        for (ObjectType objectType : objectTypes) {
            if (objectType.getLabel() == null || objectType.getLabel().trim().isEmpty()) {
                errors.add("对象类型缺少标签 [IRI: " + objectType.getIri() + "] → 修复建议: 在 Protégé 精修面板中为该类补充 rdfs:label 标签");
            }
            if (objectType.getIri() == null || objectType.getIri().trim().isEmpty()) {
                errors.add("对象类型缺少 IRI [Label: " + objectType.getLabel() + "] → 修复建议: 为该类设置有效的 IRI（如 http://example.org/ontology#类名）");
            }
        }

        // 检查关联类型的完整性
        for (LinkType linkType : linkTypes) {
            if (linkType.getLabel() == null || linkType.getLabel().trim().isEmpty()) {
                errors.add("关联类型缺少标签 [IRI: " + linkType.getIri() + "] → 修复建议: 在 Protégé 精修面板中为该属性补充 rdfs:label 标签");
            }
            if (linkType.getIri() == null || linkType.getIri().trim().isEmpty()) {
                errors.add("关联类型缺少 IRI [Label: " + linkType.getLabel() + "] → 修复建议: 为该属性设置有效的 IRI（如 http://example.org/ontology/属性名）");
            }
        }
    }

    /**
     * 逻辑一致性检查
     */
    private void checkLogicalConsistency(
            Ontology ontology,
            List<ObjectType> objectTypes,
            List<LinkType> linkTypes,
            List<String> errors,
            List<String> warnings) {

        // 检查关联类型的定义域和值域
        for (LinkType linkType : linkTypes) {
            String sourceType = linkType.getSourceType();
            String targetType = linkType.getTargetType();

            // 判断该关联类型是否为数据属性（range 为 XSD 数据类型）
            boolean isDatatypeProperty = isXsdDatatypeUri(targetType);

            if (sourceType != null && !sourceType.trim().isEmpty()) {
                boolean sourceExists = objectTypes.stream()
                        .anyMatch(ot -> ot.getIri() != null && ot.getIri().equals(sourceType));
                if (!sourceExists) {
                    errors.add("关联类型 [" + linkType.getLabel() + "] 的定义域类不存在: " + sourceType
                            + " → 修复建议: 将 rdfs:domain 改为已存在的类 IRI，或在对象类型中新增 IRI 为 " + sourceType + " 的类");
                }
            }

            if (targetType != null && !targetType.trim().isEmpty()) {
                // 数据属性的 range 为 XSD 数据类型（如 xsd:string、xsd:decimal），
                // XSD 类型不是 owl:Class，不应校验其是否存在于对象类型列表中
                if (isDatatypeProperty) {
                    continue;
                }
                boolean targetExists = objectTypes.stream()
                        .anyMatch(ot -> ot.getIri() != null && ot.getIri().equals(targetType));
                if (!targetExists) {
                    errors.add("关联类型 [" + linkType.getLabel() + "] 的值域类不存在: " + targetType
                            + " → 修复建议: 将 rdfs:range 改为已存在的类 IRI，或在对象类型中新增 IRI 为 " + targetType + " 的类");
                }
            }
        }

        // 检查对象类型的父类关系
        for (ObjectType objectType : objectTypes) {
            String parentIri = objectType.getParentIri();
            if (parentIri != null && !parentIri.trim().isEmpty()) {
                boolean parentExists = objectTypes.stream()
                        .anyMatch(ot -> ot.getIri() != null && ot.getIri().equals(parentIri));
                if (!parentExists) {
                    errors.add("对象类型 [" + objectType.getLabel() + "] 的父类不存在: " + parentIri
                            + " → 修复建议: 将 rdfs:subClassOf 改为已存在的父类 IRI，或在对象类型中新增 IRI 为 " + parentIri + " 的类");
                }
            }
        }
    }

    /**
     * 使用 HermiT 进行 OWL 2 DL 语义推理检查
     */
    private void checkWithHermiTReasoning(
            Ontology ontology,
            List<ObjectType> objectTypes,
            List<LinkType> linkTypes,
            List<String> errors,
            List<String> warnings,
            CheckLayerResult layer) {

        try {
            ReasoningService.HermiTCheckResult hermitResult =
                    reasoningService.checkConsistencyWithHermiT(ontology, objectTypes, linkTypes);

            if (!hermitResult.isConsistent()) {
                for (String inconsistency : hermitResult.getInconsistencies()) {
                    errors.add("[HermiT] 本体不一致: " + inconsistency
                            + " → 修复建议: 检查 OWL 约束是否矛盾（如 owl:disjointWith 与 rdfs:subClassOf 冲突，或 owl:allValuesFrom 与实际实例数据不符）");
                }
            }

            if (!hermitResult.getUnsatisfiableClasses().isEmpty()) {
                for (String uc : hermitResult.getUnsatisfiableClasses()) {
                    errors.add("[HermiT] 不可满足类（等同于 owl:Nothing）: " + uc
                            + " → 修复建议: 检查该类的约束定义，可能存在互斥约束或过于严格的 owl:intersectionOf 导致类为空");
                }
            }

            if (!hermitResult.getInferredHierarchy().isEmpty()) {
                warnings.add("[HermiT] 推理引擎推断出类层次结构扩展 (" + hermitResult.getInferredHierarchy().size() + " 个类有新增子类) → 修复建议: 查看推断的类层次关系，确认是否符合预期，可在 Protégé 中显式声明隐含的继承关系以优化本体结构");
            }

            if (hermitResult.getTotalAxioms() > 0) {
                // 统计性警告不需要修复建议
                warnings.add("[HermiT] 推理统计: " + hermitResult.getTotalAxioms() + " 公理, "
                        + hermitResult.getTotalClasses() + " 类, "
                        + hermitResult.getTotalObjectProperties() + " 对象属性, "
                        + "耗时 " + hermitResult.getPreparationTimeMs() + "ms");
            }

            // 添加详细检查项
            layer.getDetails().add("一致性状态: " + (hermitResult.isConsistent() ? "本体一致 ✓" : "本体不一致 ✗"));
            layer.getDetails().add("推理器: HermiT " + hermitResult.getReasonerVersion());
            layer.getDetails().add("总公理数: " + hermitResult.getTotalAxioms());
            layer.getDetails().add("类总数: " + hermitResult.getTotalClasses());
            layer.getDetails().add("对象属性: " + hermitResult.getTotalObjectProperties());
            layer.getDetails().add("数据属性: " + hermitResult.getTotalDatatypeProperties());
            layer.getDetails().add("推理耗时: " + hermitResult.getPreparationTimeMs() + "ms");
            if (!hermitResult.getInconsistencies().isEmpty()) {
                layer.getDetails().add("不一致项 (" + hermitResult.getInconsistencies().size() + "): " + String.join("; ", hermitResult.getInconsistencies()));
            }
            if (!hermitResult.getUnsatisfiableClasses().isEmpty()) {
                layer.getDetails().add("不可满足类 (" + hermitResult.getUnsatisfiableClasses().size() + "): " + String.join("; ", hermitResult.getUnsatisfiableClasses()));
            }
            if (!hermitResult.getInferredHierarchy().isEmpty()) {
                layer.getDetails().add("推断类层次 (" + hermitResult.getInferredHierarchy().size() + " 个父类): " + hermitResult.getInferredHierarchy().keySet());
            }
            if (!hermitResult.getInferredPropertyHierarchy().isEmpty()) {
                layer.getDetails().add("推断属性层次 (" + hermitResult.getInferredPropertyHierarchy().size() + " 个父属性): " + hermitResult.getInferredPropertyHierarchy().keySet());
            }

        } catch (Exception e) {
            warnings.add("HermiT 推理检查失败: " + e.getMessage() + " → 修复建议: 检查本体文件是否存在语法错误或不兼容 OWL 2 DL 的构造，尝试简化复杂约束");
            layer.getDetails().add("HermiT 推理检查失败: " + e.getMessage());
            log.warn("HermiT 推理检查失败，但继续其他检查", e);
        }
    }

    /**
     * 检查IRI唯一性
     *
     * 对象类型（类）：IRI 必须唯一，重复即报错。
     * 关联类型（属性）：区分两种情况——
     *   1) 同IRI + 同domain + 同range → 真正的重复定义，报 Error
     *   2) 同IRI + 不同domain/range → 同一属性连接多个类对，平台设计上
     *      允许这种模式（后端会在 TTL 中合并为 owl:unionOf），降级为 Info 级别提示
     */
    private void checkIriUniqueness(
            List<ObjectType> objectTypes,
            List<LinkType> linkTypes,
            List<String> errors,
            List<String> warnings) {

        // 检查对象类型IRI唯一性（类必须唯一）
        Set<String> objectTypeIris = new HashSet<>();
        for (ObjectType objectType : objectTypes) {
            if (objectType.getIri() != null) {
                if (!objectTypeIris.add(objectType.getIri())) {
                    errors.add("对象类型 IRI 重复: " + objectType.getIri()
                            + " → 修复建议: 为重复的类修改 IRI，确保每个类使用唯一的标识符");
                }
            }
        }

        // 检查关联类型IRI唯一性（区分真重复 vs 多domain/range模式）
        // key = "iri|sourceType|targetType"，用于检测完全相同的条目
        Set<String> linkTypeExactKeys = new HashSet<>();
        // key = iri，用于检测同IRI但不同domain/range的情况
        Map<String, String> linkTypeIriFirstSource = new HashMap<>();
        Map<String, String> linkTypeIriFirstTarget = new HashMap<>();
        Set<String> linkTypeIris = new LinkedHashSet<>();

        for (LinkType linkType : linkTypes) {
            String iri = linkType.getIri();
            if (iri == null) continue;

            linkTypeIris.add(iri);

            String source = linkType.getSourceType() != null ? linkType.getSourceType().trim() : "";
            String target = linkType.getTargetType() != null ? linkType.getTargetType().trim() : "";
            String exactKey = iri + "|" + source + "|" + target;

            if (!linkTypeExactKeys.add(exactKey)) {
                // 完全相同的条目：同IRI + 同domain + 同range → 真正的重复
                errors.add("关联类型 IRI 重复定义（相同domain/range）: " + iri
                        + " [domain=" + source + ", range=" + target + "]"
                        + " → 修复建议: 删除重复的属性定义条目");
            } else if (linkTypeIriFirstSource.containsKey(iri)) {
                // 同IRI但domain或range不同 → 平台允许的 owl:unionOf 模式
                // 仅在该IRI第一次出现重复时记录一条info（避免刷屏）
                if (!linkTypeIriFirstSource.get(iri).equals("__REPORTED__")) {
                    warnings.add("关联类型 IRI 多domain/range使用（平台允许）: " + iri
                            + " → 说明: 该属性连接了多组不同的类对（如 "
                            + linkTypeIriFirstSource.get(iri) + "→" + linkTypeIriFirstTarget.get(iri)
                            + " 与 " + source + "→" + target + " 等），后端生成TTL时将自动合并为 owl:unionOf 表达式");
                    linkTypeIriFirstSource.put(iri, "__REPORTED__");
                }
            } else {
                linkTypeIriFirstSource.put(iri, source);
                linkTypeIriFirstTarget.put(iri, target);
            }
        }

        // 检查对象类型和关联类型IRI是否冲突
        Set<String> allIris = new HashSet<>(objectTypeIris);
        for (String linkIri : linkTypeIris) {
            if (allIris.contains(linkIri)) {
                warnings.add("IRI在对象类型和关联类型中重复使用: " + linkIri
                        + " → 修复建议: 为关联类型分配新的唯一IRI，避免与对象类型混淆（OWL规范建议类和属性使用不同的命名空间）");
            }
            allIris.add(linkIri);
        }
    }

    /**
     * 检查循环继承
     */
    private void checkCircularInheritance(
            List<ObjectType> objectTypes,
            List<String> errors,
            List<String> warnings) {

        // 构建父类映射
        Map<String, String> parentMap = new HashMap<>();
        for (ObjectType objectType : objectTypes) {
            if (objectType.getIri() != null && objectType.getParentIri() != null) {
                parentMap.put(objectType.getIri(), objectType.getParentIri());
            }
        }

        // 检查循环
        for (String childIri : parentMap.keySet()) {
            Set<String> visited = new HashSet<>();
            String current = childIri;

            while (current != null && parentMap.containsKey(current)) {
                if (visited.contains(current)) {
                    // 收集循环路径
                    String path = String.join(" → ", visited) + " → " + current;
                    errors.add("检测到循环继承，涉及路径: " + path
                            + " → 修复建议: 打断循环链中的某个 rdfs:subClassOf 继承关系，确保类层次结构为有向无环图（DAG）");
                    break;
                }
                visited.add(current);
                current = parentMap.get(current);
            }
        }
    }

    /**
     * 收集基础结构检查的详细结果
     */
    private void collectBasicStructureDetails(Ontology ontology, List<ObjectType> objectTypes, List<LinkType> linkTypes, CheckLayerResult layer) {
        layer.getDetails().add("本体名称: " + (ontology.getName() != null && !ontology.getName().trim().isEmpty() ? ontology.getName() + " ✓" : "缺失 ✗"));
        layer.getDetails().add("对象类型数量: " + objectTypes.size());
        layer.getDetails().add("关联类型数量: " + linkTypes.size());
        if (!objectTypes.isEmpty()) {
            int completeCount = 0;
            for (ObjectType ot : objectTypes) {
                if (ot.getLabel() != null && !ot.getLabel().trim().isEmpty() && ot.getIri() != null && !ot.getIri().trim().isEmpty()) {
                    completeCount++;
                }
            }
            layer.getDetails().add("对象类型完整性: " + completeCount + "/" + objectTypes.size() + " 具有完整标签和IRI");
        }
        if (!linkTypes.isEmpty()) {
            int completeCount = 0;
            for (LinkType lt : linkTypes) {
                if (lt.getLabel() != null && !lt.getLabel().trim().isEmpty() && lt.getIri() != null && !lt.getIri().trim().isEmpty()) {
                    completeCount++;
                }
            }
            layer.getDetails().add("关联类型完整性: " + completeCount + "/" + linkTypes.size() + " 具有完整标签和IRI");
        }
    }

    /**
     * 收集逻辑一致性检查的详细结果
     */
    private void collectLogicalConsistencyDetails(List<ObjectType> objectTypes, List<LinkType> linkTypes, CheckLayerResult layer) {
        int domainOk = 0, rangeOk = 0, domainTotal = 0, rangeTotal = 0;
        int datatypePropCount = 0;
        int parentOk = 0, parentTotal = 0;
        for (LinkType lt : linkTypes) {
            if (lt.getSourceType() != null && !lt.getSourceType().trim().isEmpty()) {
                domainTotal++;
                if (objectTypes.stream().anyMatch(ot -> ot.getIri() != null && ot.getIri().equals(lt.getSourceType()))) {
                    domainOk++;
                }
            }
            if (lt.getTargetType() != null && !lt.getTargetType().trim().isEmpty()) {
                // 数据属性的 range 为 XSD 类型，不属于类 IRI 校验范围
                if (isXsdDatatypeUri(lt.getTargetType())) {
                    datatypePropCount++;
                    continue;
                }
                rangeTotal++;
                if (objectTypes.stream().anyMatch(ot -> ot.getIri() != null && ot.getIri().equals(lt.getTargetType()))) {
                    rangeOk++;
                }
            }
        }
        for (ObjectType ot : objectTypes) {
            if (ot.getParentIri() != null && !ot.getParentIri().trim().isEmpty()) {
                parentTotal++;
                if (objectTypes.stream().anyMatch(o -> o.getIri() != null && o.getIri().equals(ot.getParentIri()))) {
                    parentOk++;
                }
            }
        }
        layer.getDetails().add("定义域引用检查: " + domainOk + "/" + domainTotal + " 有效");
        layer.getDetails().add("值域引用检查: " + rangeOk + "/" + rangeTotal + " 有效" + (datatypePropCount > 0 ? "（另有 " + datatypePropCount + " 个数据属性使用 XSD 类型作值域，不参与校验）" : ""));
        layer.getDetails().add("父类引用检查: " + parentOk + "/" + parentTotal + " 有效");
    }

    /**
     * 收集IRI唯一性检查的详细结果
     */
    private void collectIriUniquenessDetails(List<ObjectType> objectTypes, List<LinkType> linkTypes, CheckLayerResult layer) {
        layer.getDetails().add("对象类型IRI检查: " + objectTypes.size() + " 个");
        layer.getDetails().add("关联类型IRI检查: " + linkTypes.size() + " 个");

        // 统计对象类型IRI
        Set<String> objectTypeIris = objectTypes.stream().map(ObjectType::getIri).filter(Objects::nonNull).collect(java.util.stream.Collectors.toSet());
        long objUniqueCount = objectTypeIris.size();
        long objDupCount = objectTypes.stream().map(ObjectType::getIri).filter(Objects::nonNull).count() - objUniqueCount;
        layer.getDetails().add("对象类型IRI唯一: " + objUniqueCount + " 个" + (objDupCount > 0 ? "，重复: " + objDupCount + " 个 ✗" : " ✓"));

        // 统计关联类型IRI（区分真重复 vs 多domain/range模式）
        Set<String> linkTypeIris = new LinkedHashSet<>();
        Set<String> exactKeys = new HashSet<>();
        Map<String, List<String>> iriSourceTargets = new LinkedHashMap<>(); // iri -> ["src→tgt", ...]
        for (LinkType lt : linkTypes) {
            String iri = lt.getIri();
            if (iri == null) continue;
            linkTypeIris.add(iri);
            String src = lt.getSourceType() != null ? lt.getSourceType().trim() : "";
            String tgt = lt.getTargetType() != null ? lt.getTargetType().trim() : "";
            String exactKey = iri + "|" + src + "|" + tgt;
            exactKeys.add(exactKey);
            iriSourceTargets.computeIfAbsent(iri, k -> new ArrayList<>()).add(src + " → " + tgt);
        }
        long uniqueIriCount = linkTypeIris.size();
        long totalEntryCount = linkTypes.stream().filter(lt -> lt.getIri() != null).count();
        long multiDomainCount = iriSourceTargets.values().stream().filter(v -> v.size() > 1).count();
        long exactDupCount = totalEntryCount - exactKeys.size();

        layer.getDetails().add("关联类型IRI唯一: " + uniqueIriCount + " 个（总计 " + totalEntryCount + " 条定义）");
        if (multiDomainCount > 0) {
            layer.getDetails().add("多domain/range属性（平台允许）: " + multiDomainCount + " 个 → 后端生成TTL时自动合并为 owl:unionOf");
        }
        if (exactDupCount > 0) {
            layer.getDetails().add("完全重复定义（同IRI+同domain+同range）: " + exactDupCount + " 条 ✗");
        }

        // 跨类型IRI冲突检查
        Set<String> overlap = new HashSet<>(objectTypeIris);
        overlap.retainAll(linkTypeIris);
        if (!overlap.isEmpty()) {
            layer.getDetails().add("跨类型IRI冲突: " + overlap.size() + " 个 ✗");
        } else {
            layer.getDetails().add("跨类型IRI冲突: 无 ✓");
        }
    }

    /**
     * 收集循环继承检查的详细结果
     */
    private void collectCircularInheritanceDetails(List<ObjectType> objectTypes, CheckLayerResult layer) {
        long hasParentCount = objectTypes.stream().filter(ot -> ot.getParentIri() != null && !ot.getParentIri().trim().isEmpty()).count();
        layer.getDetails().add("定义了继承关系的类: " + hasParentCount + " 个");
        layer.getDetails().add("无继承关系的类（根类）: " + (objectTypes.size() - hasParentCount) + " 个");
    }

    /**
     * 判断 URI 是否为 XSD 数据类型（非 owl:Class，不应作为对象属性的 range 校验）。
     * 数据属性（owl:DatatypeProperty）的 rdfs:range 是 XSD 字面量类型，不属于对象类型列表。
     */
    private boolean isXsdDatatypeUri(String uri) {
        if (uri == null || uri.isBlank()) return false;
        String u = uri.trim();
        return u.startsWith("http://www.w3.org/2001/XMLSchema#")
                || u.startsWith("http://www.w3.org/2000/10/XMLSchema#")
                || u.startsWith("http://www.w3.org/1999/02/22-rdf-syntax-ns#")
                || u.equals("http://www.w3.org/2000/01/rdf-schema#Literal");
    }

    /**
     * 一致性检查结果
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ConsistencyCheckResult {
        private String ontologyId;
        private String ontologyName;
        private boolean valid;
        @Builder.Default private List<String> errors = new ArrayList<>();
        @Builder.Default private List<String> warnings = new ArrayList<>();
        private int classCount;
        private int propertyCount;
        private Date checkedAt;
        @Builder.Default private List<CheckLayerResult> layers = new ArrayList<>();
    }

    /**
     * 单层检查结果
     */
    @Data
    public static class CheckLayerResult {
        private String name;
        private String description;
        private boolean passed;
        private List<String> errors = new ArrayList<>();
        private List<String> warnings = new ArrayList<>();
        private List<String> details = new ArrayList<>();
    }
}