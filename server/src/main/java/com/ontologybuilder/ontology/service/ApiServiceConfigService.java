package com.ontologybuilder.ontology.service;

import com.ontologybuilder.ontology.model.ApiServiceConfig;

import java.util.List;

public interface ApiServiceConfigService {

    List<ApiServiceConfig> findAll();

    ApiServiceConfig findById(String id);

    ApiServiceConfig create(ApiServiceConfig config);

    ApiServiceConfig update(String id, ApiServiceConfig config);

    void delete(String id);
}
