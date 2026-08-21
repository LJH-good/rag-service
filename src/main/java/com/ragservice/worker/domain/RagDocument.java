package com.ragservice.worker.domain;

import com.ragservice.worker.domain.enums.UserType;
import jakarta.persistence.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcType;
import org.hibernate.annotations.UpdateTimestamp;
import org.hibernate.dialect.PostgreSQLEnumJdbcType;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * RAG 처리 대상 문서 엔티티 (rag_documents).
 */
@Entity
@Table(name = "rag_documents")
public class RagDocument {

    @Id
    @Column(name = "id")
    private UUID id;

    @Column(name = "file_id", nullable = false)
    private UUID fileId;

    @Column(name = "category_id")
    private UUID categoryId;

    @Enumerated(EnumType.STRING)
    @JdbcType(PostgreSQLEnumJdbcType.class)
    @Column(name = "user_type", nullable = false, columnDefinition = "rag.user_type")
    private UserType userType;

    @Column(name = "user_no", columnDefinition = "uuid", nullable = false)
    private UUID userNo;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false, nullable = false)
    private OffsetDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    @Column(name = "is_deleted", nullable = false)
    private boolean isDeleted = false;

    @Column(name = "deleted_at")
    private OffsetDateTime deletedAt;

    @Column(name = "purged_at")
    private OffsetDateTime purgedAt;

    protected RagDocument() {}

    public RagDocument(UUID id, UUID fileId, UUID categoryId, UserType userType, UUID userNo) {
        this.id = id;
        this.fileId = fileId;
        this.categoryId = categoryId;
        this.userType = userType;
        this.userNo = userNo;
    }

    public UUID getId() { return id; }
    public UUID getDocumentId() { return id; }
    public UUID getFileId() { return fileId; }
    public UUID getCategoryId() { return categoryId; }
    public UserType getUserType() { return userType; }
    public UUID getUserNo() { return userNo; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public boolean isDeleted() { return isDeleted; }
    public OffsetDateTime getDeletedAt() { return deletedAt; }
    public OffsetDateTime getPurgedAt() { return purgedAt; }
    public boolean isPurged() { return purgedAt != null; }

    public void softDelete(OffsetDateTime at) {
        this.isDeleted = true;
        this.deletedAt = at;
    }

    public void markPurged(OffsetDateTime at) {
        this.purgedAt = at;
    }
}
