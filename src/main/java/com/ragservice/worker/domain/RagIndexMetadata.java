package com.ragservice.worker.domain;

import jakarta.persistence.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.OffsetDateTime;
import java.util.UUID;

@Entity
@Table(
        name = "rag_index_metadata",
        uniqueConstraints = @UniqueConstraint(
                name = "uq_rag_index_metadata_document",
                columnNames = {"document_id"}
        )
)
public class RagIndexMetadata {

    @Id
    @Column(name = "id")
    private UUID id;

    @Column(name = "document_id", nullable = false)
    private UUID documentId;

    @Column(name = "collection", length = 128, nullable = false)
    private String collection;

    @Column(name = "embedding_model", length = 128)
    private String embeddingModel;

    @Column(name = "embedding_dim")
    private Integer embeddingDim;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false, nullable = false)
    private OffsetDateTime createdAt;

    @Column(name = "indexed_at", nullable = false)
    private OffsetDateTime indexedAt;

    protected RagIndexMetadata() {}

    public RagIndexMetadata(UUID id, UUID documentId, String collection,
                            String embeddingModel, Integer embeddingDim) {
        this.id = id;
        this.documentId = documentId;
        this.collection = collection;
        this.embeddingModel = embeddingModel;
        this.embeddingDim = embeddingDim;
        this.indexedAt = OffsetDateTime.now();
    }

    public void refreshIndexedAt() {
        this.indexedAt = OffsetDateTime.now();
    }

    public UUID getId() { return id; }
    public UUID getDocumentId() { return documentId; }
    public String getCollection() { return collection; }
    public String getEmbeddingModel() { return embeddingModel; }
    public Integer getEmbeddingDim() { return embeddingDim; }
    public OffsetDateTime getIndexedAt() { return indexedAt; }
}
