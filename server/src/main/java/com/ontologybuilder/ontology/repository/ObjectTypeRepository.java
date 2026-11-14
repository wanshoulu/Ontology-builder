package com.ontologybuilder.ontology.repository;

import com.ontologybuilder.ontology.model.ObjectType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * 对象类型数据访问层
 */
@Repository
public interface ObjectTypeRepository extends JpaRepository<ObjectType, String> {

    /**
     * 根据本体ID查找对象类型列表
     */
    List<ObjectType> findByOntologyId(String ontologyId);

    /**
     * 根据本体ID和IRI查找
     */
    List<ObjectType> findByOntologyIdAndIri(String ontologyId, String iri);

    /**
     * 根据本体ID删除所有对象类型
     */
    @Modifying
    @Query("DELETE FROM ObjectType ot WHERE ot.ontologyId = :ontologyId")
    void deleteByOntologyId(String ontologyId);
}
