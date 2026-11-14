package com.ontologybuilder.ontology.service.owl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ontologybuilder.ontology.model.Ontology;
import com.ontologybuilder.ontology.model.ObjectType;
import com.ontologybuilder.ontology.model.LinkType;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * OWL 2 本体序列化器（简化版）
 * 支持 RDF/XML, Turtle, JSON-LD 格式
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OntologyOwlExporter {

    private final ObjectMapper objectMapper;

    /**
     * 将本体导出为指定格式的 OWL 字符串（简化实现）
     */
    public String exportToOwl(Ontology ontology, List<ObjectType> objectTypes,
                              List<LinkType> linkTypes, String format) {
        log.info("简化版OWL导出：本体 {}，对象类型 {} 个，关联类型 {} 个，格式 {}",
                ontology.getName(), objectTypes.size(), linkTypes.size(), format);
        // 返回一个简单的RDF/Turtle格式的字符串
        StringBuilder owl = new StringBuilder();
        owl.append("@prefix : <http://ontologybuilder.local/ontology/").append(ontology.getId()).append("#> .\n");
        owl.append("@prefix owl: <http://www.w3.org/2002/07/owl#> .\n");
        owl.append("@prefix rdfs: <http://www.w3.org/2000/01/rdf-schema#> .\n");
        owl.append("@prefix xsd: <http://www.w3.org/2001/XMLSchema#> .\n\n");

        owl.append(":Ontology a owl:Ontology ;\n");
        owl.append("  rdfs:label \"").append(ontology.getName()).append("\"@zh .\n\n");

        for (ObjectType obj : objectTypes) {
            owl.append(":").append(obj.getIri()).append(" a owl:Class ;\n");
            if (obj.getLabel() != null && !obj.getLabel().isBlank()) {
                owl.append("  rdfs:label \"").append(escapeTtl(obj.getLabel())).append("\"@zh");
            }
            if (obj.getParentIri() != null && !obj.getParentIri().isBlank()) {
                owl.append(" ;\n  rdfs:subClassOf :").append(obj.getParentIri());
            }
            owl.append(" .\n\n");
        }

        // 分离对象属性和数据属性：range 为 XSD 数据类型的 LinkType 应输出为 owl:DatatypeProperty
        for (LinkType link : linkTypes) {
            if (isXsdDatatypeRange(link.getTargetType())) {
                owl.append(":").append(link.getIri()).append(" a owl:DatatypeProperty ;\n");
                if (link.getLabel() != null && !link.getLabel().isBlank()) {
                    owl.append("  rdfs:label \"").append(escapeTtl(link.getLabel())).append("\"@zh");
                }
                if (link.getSourceType() != null && !link.getSourceType().isBlank()) {
                    owl.append(" ;\n  rdfs:domain :").append(link.getSourceType());
                }
                if (link.getTargetType() != null && !link.getTargetType().isBlank()) {
                    owl.append(" ;\n  rdfs:range ").append(formatRange(link.getTargetType()));
                } else {
                    owl.append(" ;\n  rdfs:range xsd:string");
                }
                owl.append(" .\n\n");
            } else {
                owl.append(":").append(link.getIri()).append(" a owl:ObjectProperty ;\n");
                if (link.getLabel() != null && !link.getLabel().isBlank()) {
                    owl.append("  rdfs:label \"").append(escapeTtl(link.getLabel())).append("\"@zh");
                }
                if (link.getSourceType() != null && !link.getSourceType().isBlank()) {
                    owl.append(" ;\n  rdfs:domain :").append(link.getSourceType());
                }
                if (link.getTargetType() != null && !link.getTargetType().isBlank()) {
                    owl.append(" ;\n  rdfs:range :").append(link.getTargetType());
                }
                owl.append(" .\n\n");
            }
        }

        // 导出数据属性（DatatypeProperty）从 ObjectType 的 propertiesJson 中提取
        for (ObjectType ot : objectTypes) {
            if (ot.getPropertiesJson() != null && !ot.getPropertiesJson().isBlank()) {
                try {
                    @SuppressWarnings("unchecked")
                    List<Map<String, String>> dataProps = objectMapper.readValue(
                            ot.getPropertiesJson(), List.class);
                    for (Map<String, String> dp : dataProps) {
                        String dpIri = dp.get("iri");
                        if (dpIri == null || dpIri.isBlank()) continue;
                        // 提取短名用于 :prefix 格式
                        String shortName = dpIri.contains("#") ? dpIri.substring(dpIri.lastIndexOf('#') + 1)
                                : dpIri.substring(dpIri.lastIndexOf('/') + 1);
                        owl.append(":").append(shortName).append(" a owl:DatatypeProperty ;\n");
                        String dpLabel = dp.get("label");
                        if (dpLabel != null && !dpLabel.isBlank()) {
                            owl.append("  rdfs:label \"").append(escapeTtl(dpLabel)).append("\"@zh ;\n");
                        }
                        owl.append("  rdfs:domain :").append(ot.getIri()).append(" ;\n");
                        String dpRange = dp.get("range");
                        if (dpRange != null && !dpRange.isBlank()) {
                            owl.append("  rdfs:range ").append(formatRange(dpRange)).append(" .\n\n");
                        } else {
                            owl.append("  rdfs:range xsd:string .\n\n");
                        }
                    }
                } catch (Exception e) {
                    log.warn("解析 ObjectType {} 的 propertiesJson 失败: {}", ot.getId(), e.getMessage());
                }
            }
        }

        return owl.toString();
    }

    private boolean isXsdDatatypeRange(String rangeUri) {
        if (rangeUri == null || rangeUri.isBlank()) return false;
        String u = rangeUri.trim();
        return u.startsWith("http://www.w3.org/2001/XMLSchema#")
                || u.startsWith("http://www.w3.org/2000/10/XMLSchema#")
                || u.startsWith("http://www.w3.org/1999/02/22-rdf-syntax-ns#")
                || u.equals("http://www.w3.org/2000/01/rdf-schema#Literal");
    }

    private String formatRange(String range) {
        if (range == null || range.isBlank()) return "xsd:string";
        String trimmed = range.trim();
        if (trimmed.startsWith("<") && trimmed.endsWith(">")) return trimmed;
        if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) {
            return "<" + trimmed + ">";
        }
        return trimmed;
    }

    private String escapeTtl(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r");
    }
}