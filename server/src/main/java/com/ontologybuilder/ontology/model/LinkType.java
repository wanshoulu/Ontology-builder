package com.ontologybuilder.ontology.model;

import jakarta.persistence.*;
import lombok.*;
import java.time.LocalDateTime;

/**
 * 本体链接类型
 */
@Data
@Entity
@Table(name = "tm_link_type")
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class LinkType {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private String id;

    @Column(name = "ontology_id", nullable = false)
    private String ontologyId;

    @Column(nullable = false, length = 500)
    private String iri;

    @Column(nullable = false, length = 200)
    private String label;

    @Column(name = "source_type", length = 500)
    private String sourceType;

    @Column(name = "target_type", length = 500)
    private String targetType;

    @Column(columnDefinition = "TEXT")
    private String description;

    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() { createdAt = LocalDateTime.now(); }
}
