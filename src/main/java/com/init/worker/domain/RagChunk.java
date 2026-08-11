package com.init.worker.domain;

import jakarta.persistence.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.OffsetDateTime;
import java.util.UUID;

@Entity
@Table(
        name = "rag_chunks",
        uniqueConstraints = @UniqueConstraint(
                name = "uq_rag_chunks_document_index",
                columnNames = {"document_id", "chunk_index"}
        )
)
public class RagChunk {

    @Id
    @Column(name = "id")
    private UUID id;

    @Column(name = "document_id", nullable = false)
    private UUID documentId;

    @Column(name = "chunk_index", nullable = false)
    private int chunkIndex;

    @Column(name = "storage_key")
    private String storageKey;

    @Column(name = "char_count")
    private Integer charCount;

    @Column(name = "location")
    private String location;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false, nullable = false)
    private OffsetDateTime createdAt;

    protected RagChunk() {}

    public RagChunk(UUID id, UUID documentId, int chunkIndex,
                    String storageKey, Integer charCount) {
        this.id = id;
        this.documentId = documentId;
        this.chunkIndex = chunkIndex;
        this.storageKey = storageKey;
        this.charCount = charCount;
    }

    public RagChunk(UUID id, UUID documentId, int chunkIndex,
                    String storageKey, Integer charCount, String location) {
        this(id, documentId, chunkIndex, storageKey, charCount);
        this.location = location;
    }

    public UUID getId() { return id; }
    public UUID getChunkId() { return id; }
    public UUID getDocumentId() { return documentId; }
    public int getChunkIndex() { return chunkIndex; }
    public String getStorageKey() { return storageKey; }
    public Integer getCharCount() { return charCount; }
    public String getLocation() { return location; }

    /** 원본 file.storage_key 기준 canonical 경로로 DB 메타를 맞출 때 사용 */
    public void updateStorageKey(String storageKey) {
        this.storageKey = storageKey;
    }
}
