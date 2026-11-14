package com.ontologybuilder.ontology.model;

import jakarta.persistence.*;
import lombok.*;
import java.time.LocalDateTime;

/**
 * 本体对象类型
 */
@Data
@Entity
@Table(name = "tm_object_type")
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ObjectType {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private String id;

    @Column(name = "ontology_id", nullable = false)
    private String ontologyId;

    @Column(nullable = false, length = 500)
    private String iri;

    @Column(nullable = false, length = 200)
    private String label;

    @Column(name = "parent_iri", length = 500)
    private String parentIri;

    @Column(columnDefinition = "TEXT")
    private String description;

    @Column(name = "properties_json", columnDefinition = "TEXT")
    private String propertiesJson;

    @Column(name = "position_json", columnDefinition = "TEXT")
    private String positionJson;

    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() { createdAt = LocalDateTime.now(); }
}
