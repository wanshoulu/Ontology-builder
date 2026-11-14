package com.ontologybuilder.instantiation.repository;

import com.ontologybuilder.instantiation.model.OntologyInstance;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface OntologyInstanceRepository extends JpaRepository<OntologyInstance, String> {
    List<OntologyInstance> findByTenantIdOrderByCreatedAtDesc(String tenantId);
    List<OntologyInstance> findByTenantId(String tenantId);
    boolean existsByTenantIdAndName(String tenantId, String name);
    boolean existsByOntologyId(String ontologyId);
    long countByOntologyId(String ontologyId);

    /** 统计有实例的租户数（排除 default 系统租户） */
    @Query("SELECT COUNT(DISTINCT i.tenantId) FROM OntologyInstance i WHERE i.tenantId <> 'default'")
    long countDistinctTenantsExcludingDefault();

    /** 统计非 default 租户的实例总数 */
    @Query("SELECT COUNT(i) FROM OntologyInstance i WHERE i.tenantId <> 'default'")
    long countExcludingDefaultTenant();
}
