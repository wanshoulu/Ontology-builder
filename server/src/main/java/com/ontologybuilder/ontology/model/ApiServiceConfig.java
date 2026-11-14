package com.ontologybuilder.ontology.model;

import jakarta.persistence.*;
import lombok.*;
import java.time.LocalDateTime;

/**
 * 本体 API 服务配置实体
 */
@Data
@Entity
@Table(name = "tm_api_service_config")
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ApiServiceConfig {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private String id;

    @Column(name = "tenant_id", nullable = false)
    @Builder.Default
    private String tenantId = "default";

    @Column(nullable = false, length = 200)
    private String name;

    @Column(name = "ontology_id", length = 200)
    private String ontologyId;

    @Column(name = "base_url", nullable = false, length = 500)
    private String baseUrl;

    @Column(name = "api_type", nullable = false, length = 20)
    @Builder.Default
    private String apiType = "sparql";

    @Column(nullable = false)
    @Builder.Default
    private Boolean enabled = true;

    @Column(name = "max_query_limit", nullable = false)
    @Builder.Default
    private Integer maxQueryLimit = 1000;

    @Column(nullable = false)
    @Builder.Default
    private Integer timeout = 30;

    @Column(name = "auth_enabled", nullable = false)
    @Builder.Default
    private Boolean authEnabled = false;

    @Column(name = "auth_token", length = 500)
    private String authToken;

    @Column(length = 500)
    private String description;

    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
        updatedAt = LocalDateTime.now();
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }
}
