package com.init.worker.domain;

import jakarta.persistence.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * 브리지(그래프 ↔ 청크 ↔ 벡터). 그래프 탐색으로 도달한 엔티티를
 * 실제 청크(citation) 텍스트로 환원하기 위한 연결 테이블.
 */
@Entity
@Table(
        name = "rag_entity_chunk",
        uniqueConstraints = @UniqueConstraint(
                name = "uq_rag_entity_chunk",
                columnNames = {"entity_id", "chunk_id"}
        ),
        indexes = {
                @Index(name = "idx_rag_entity_chunk_entity", columnList = "entity_id"),
                @Index(name = "idx_rag_entity_chunk_chunk", columnList = "chunk_id")
        }
)
public class RagEntityChunk {

    @Id
    @Column(name = "id")
    private UUID id;

    @Column(name = "entity_id", nullable = false)
    private UUID entityId;

    @Column(name = "chunk_id", nullable = false)
    private UUID chunkId;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false, nullable = false)
    private OffsetDateTime createdAt;

    @Column(name = "is_deleted", nullable = false)
    private boolean isDeleted = false;

    @Column(name = "deleted_at")
    private OffsetDateTime deletedAt;

    protected RagEntityChunk() {}

    public RagEntityChunk(UUID id, UUID entityId, UUID chunkId) {
        this.id = id;
        this.entityId = entityId;
        this.chunkId = chunkId;
    }

    public UUID getId() { return id; }
    public UUID getEntityId() { return entityId; }
    public UUID getChunkId() { return chunkId; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public boolean isDeleted() { return isDeleted; }
    public OffsetDateTime getDeletedAt() { return deletedAt; }

    public void softDelete(OffsetDateTime at) {
        this.isDeleted = true;
        this.deletedAt = at;
    }
}
