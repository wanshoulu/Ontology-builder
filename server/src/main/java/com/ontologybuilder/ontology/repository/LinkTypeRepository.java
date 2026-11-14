package com.ontologybuilder.ontology.repository;

import com.ontologybuilder.ontology.model.LinkType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * 关联类型数据访问层
 */
@Repository
public interface LinkTypeRepository extends JpaRepository<LinkType, String> {

    /**
     * 根据本体ID查找关联类型列表
     */
    List<LinkType> findByOntologyId(String ontologyId);

    /**
     * 根据本体ID删除所有关联类型
     */
    @Modifying
    @Query("DELETE FROM LinkType lt WHERE lt.ontologyId = :ontologyId")
    void deleteByOntologyId(String ontologyId);
}
