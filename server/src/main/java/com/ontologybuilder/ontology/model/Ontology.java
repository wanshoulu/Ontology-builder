package com.ontologybuilder.ontology.model;

import jakarta.persistence.*;
import lombok.*;
import java.time.LocalDateTime;

/**
 * 本体实体
 */
@Data
@Entity
@Table(name = "tm_ontology")
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Ontology {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private String id;

    @Column(name = "tenant_id", nullable = false)
    @Builder.Default
    private String tenantId = "default";

    @Column(nullable = false, length = 100)
    private String name;

    @Column(columnDefinition = "TEXT")
    private String description;

    @Column(length = 20)
    @Builder.Default
    private String version = "1.0.0";

    @Column(name = "version_name", length = 200)
    private String versionName;

    @Column(name = "industry_ontology", length = 20)
    @Enumerated(EnumType.STRING)
    private IndustryOntology industryOntology;

    @Column(length = 20)
    @Enumerated(EnumType.STRING)
    @Builder.Default
    private Status status = Status.draft;

    @Column(name = "class_count")
    @Builder.Default
    private Integer classCount = 0;

    @Column(name = "property_count")
    @Builder.Default
    private Integer propertyCount = 0;

    @Column(name = "ttl_path", length = 500)
    private String ttlPath;

    @Column(name = "instantiated")
    @Builder.Default
    private Boolean instantiated = false;

    @Column(name = "source_md_file_name", length = 500)
    private String sourceMdFileName;

    @Column(name = "source_md_file_id", length = 36)
    private String sourceMdFileId;

    @Column(name = "source_model_name", length = 200)
    private String sourceModelName;

    @Column(name = "ttl_generated_at")
    private LocalDateTime ttlGeneratedAt;

    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @PrePersist
    protected void onCreate() { createdAt = LocalDateTime.now(); updatedAt = LocalDateTime.now(); }
    @PreUpdate
    protected void onUpdate() { updatedAt = LocalDateTime.now(); }

    public enum IndustryOntology { fibo, iof, custom, none }
    public enum Status { draft, active, deprecated }
}
