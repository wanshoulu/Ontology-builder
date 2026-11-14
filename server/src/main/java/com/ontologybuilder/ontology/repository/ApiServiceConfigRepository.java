package com.ontologybuilder.ontology.repository;

import com.ontologybuilder.ontology.model.ApiServiceConfig;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface ApiServiceConfigRepository extends JpaRepository<ApiServiceConfig, String> {
    List<ApiServiceConfig> findByTenantId(String tenantId);
    List<ApiServiceConfig> findByOntologyId(String ontologyId);
}
