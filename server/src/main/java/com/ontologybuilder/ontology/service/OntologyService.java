package com.ontologybuilder.ontology.service;

import com.ontologybuilder.ontology.model.LinkType;
import com.ontologybuilder.ontology.model.ObjectType;
import com.ontologybuilder.ontology.model.Ontology;

import java.util.List;
import java.util.Map;

/**
 * 本体管理服务接口
 */
public interface OntologyService {

    Ontology create(Ontology ontology);
    Ontology getById(String id);
    List<Ontology> list(String tenantId);
    Ontology update(String id, Ontology ontology);
    void delete(String id);

    Map<String, Object> batchDelete(List<String> ids);

    ObjectType createObjectType(String ontologyId, ObjectType objectType);
    List<ObjectType> listObjectTypes(String ontologyId);
    ObjectType updateObjectType(String id, ObjectType objectType);
    void deleteObjectType(String id);
    ObjectType updateObjectPosition(String objectTypeId, Map<String, Double> position);

    LinkType createLinkType(String ontologyId, LinkType linkType);
    List<LinkType> listLinkTypes(String ontologyId);
    LinkType updateLinkType(String id, LinkType linkType);
    void deleteLinkType(String id);

    String exportOntology(String id, String format);
    Map<String, Object> importOntology(String id, String content, String format);
    Map<String, Object> validateOntology(String id);

    // 以下方法用于兼容控制器
    List<Ontology> findAll();
    Ontology findById(String id);
    List<ObjectType> findObjectTypes(String ontologyId);
    List<LinkType> findLinkTypes(String ontologyId);
    Map<String, Object> exportOntology(String id);
    Ontology importOntology(Map<String, Object> importData);
    Map<String, Object> validate(String id);

    /**
     * 根据校验错误自动修复本体（数据库记录 + TTL 文件）
     * @param id 本体 ID
     * @param selectedErrorIndices 用户选择的错误索引列表（0-based）
     * @return 修复结果，包含修复详情和更新后的 TTL 内容
     */
    Map<String, Object> autoFix(String id, List<Integer> selectedErrorIndices);

    /**
     * 根据校验错误和警告自动修复本体（数据库记录 + TTL 文件）
     * @param id 本体 ID
     * @param selectedErrorIndices 用户选择的错误索引列表（0-based）
     * @param selectedWarningIndices 用户选择的警告索引列表（0-based），可选
     * @return 修复结果，包含修复详情和更新后的 TTL 内容
     */
    Map<String, Object> autoFix(String id, List<Integer> selectedErrorIndices, List<Integer> selectedWarningIndices);
}
