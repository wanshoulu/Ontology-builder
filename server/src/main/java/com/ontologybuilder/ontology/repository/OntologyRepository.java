package com.ontologybuilder.ontology.repository;

import com.ontologybuilder.ontology.model.Ontology;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface OntologyRepository extends JpaRepository<Ontology, String> {
    List<Ontology> findByTenantId(String tenantId);
    List<Ontology> findByTenantIdAndIndustryOntology(String tenantId, Ontology.IndustryOntology industryOntology);
}
