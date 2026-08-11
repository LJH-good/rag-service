package com.init.worker.domain;

import com.init.worker.domain.enums.RagEmbeddingPartStatus;
import jakarta.persistence.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.OffsetDateTime;
import java.util.UUID;

@Entity
@Table(name = "rag_embedding_parts")
public class RagEmbeddingPart {

    @Id
    @Column(name = "id")
    private UUID id;

    @Column(name = "chunk_id", nullable = false)
    private UUID chunkId;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", length = 16, nullable = false)
    private RagEmbeddingPartStatus status;

    @Column(name = "point_count")
    private Long pointCount;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false, nullable = false)
    private OffsetDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    protected RagEmbeddingPart() {}

    public RagEmbeddingPart(UUID id, UUID chunkId, RagEmbeddingPartStatus status) {
        this.id = id;
        this.chunkId = chunkId;
        this.status = status;
    }

    public void markUpserted(long pointCount) {
        this.status = RagEmbeddingPartStatus.UPSERTED;
        this.pointCount = pointCount;
    }

    public void markFailed() {
        this.status = RagEmbeddingPartStatus.FAILED;
    }

    public UUID getId() { return id; }
    public UUID getChunkId() { return chunkId; }
    public RagEmbeddingPartStatus getStatus() { return status; }
    public Long getPointCount() { return pointCount; }
}
