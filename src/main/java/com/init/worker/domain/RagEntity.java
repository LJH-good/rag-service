package com.init.worker.domain;

import jakarta.persistence.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * 그래프 엔티티(개념). Pass1(EXTRACT_ENTITY)이 문서 단위 canonical 로 확정한다.
 * 동의어는 entity resolution 으로 병합되어 (document_id, type, name) 당 한 행을 유지한다.
 */
@Entity
@Table(
        name = "rag_entity",
        uniqueConstraints = @UniqueConstraint(
                name = "uq_rag_entity_document_type_name",
                columnNames = {"document_id", "type", "name"}
        )
)
public class RagEntity {

    @Id
    @Column(name = "entity_id")
    private UUID entityId;

    @Column(name = "document_id", nullable = false)
    private UUID documentId;

    @Column(name = "name", nullable = false)
    private String name;

    @Column(name = "type", nullable = false)
    private String type;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false, nullable = false)
    private OffsetDateTime createdAt;

    @Column(name = "is_deleted", nullable = false)
    private boolean isDeleted = false;

    @Column(name = "deleted_at")
    private OffsetDateTime deletedAt;

    protected RagEntity() {}

    public RagEntity(UUID entityId, UUID documentId, String name, String type) {
        this.entityId = entityId;
        this.documentId = documentId;
        this.name = name;
        this.type = type;
    }

    public UUID getEntityId() { return entityId; }
    public UUID getDocumentId() { return documentId; }
    public String getName() { return name; }
    public String getType() { return type; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public boolean isDeleted() { return isDeleted; }
    public OffsetDateTime getDeletedAt() { return deletedAt; }

    public void softDelete(OffsetDateTime at) {
        this.isDeleted = true;
        this.deletedAt = at;
    }
}
