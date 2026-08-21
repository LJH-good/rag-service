package com.ragservice.worker.domain;

import com.ragservice.worker.domain.enums.FileStatus;
import jakarta.persistence.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * 물리 파일 메타 엔티티 (rag_document_files).
 * - 파일 업로드 상태, 스토리지 경로, 체크섬 등 파일 수준의 정보를 보관한다.
 * - rag_documents 와 1:1 대응한다.
 */
@Entity
@Table(name = "rag_document_files")
public class RagDocumentFile {

    @Id
    @Column(name = "id")
    private UUID id;

    @Column(name = "title")
    private String title;

    @Column(name = "original_file_name", nullable = false)
    private String originalFileName;

    @Column(name = "storage_key")
    private String storageKey;

    @Column(name = "file_size")
    private Long fileSize;

    @Column(name = "checksum", length = 64)
    private String checksum;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", length = 30, nullable = false)
    private FileStatus status;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false, nullable = false)
    private OffsetDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    protected RagDocumentFile() {}

    public RagDocumentFile(UUID id, String title, String originalFileName) {
        this.id = id;
        this.title = title;
        this.originalFileName = originalFileName;
        this.status = FileStatus.PENDING;
    }

    public void updateTitle(String title) {
        this.title = title;
    }

    public void markUploaded(String storageKey, long fileSize, String checksum) {
        this.storageKey = storageKey;
        this.fileSize = fileSize;
        this.checksum = checksum;
        this.status = FileStatus.UPLOADED;
    }

    public void markIndexed() {
        this.status = FileStatus.INDEXED;
    }

    public void markFailed() {
        this.status = FileStatus.FAILED;
    }

    /** 물리 스토리지 퍼지 후 메타만 보존할 때 storage_key 를 비운다. */
    public void clearStorageAfterPurge() {
        this.storageKey = null;
    }

    public UUID getId() { return id; }
    public String getTitle() { return title; }
    public String getOriginalFileName() { return originalFileName; }
    public String getStorageKey() { return storageKey; }
    public Long getFileSize() { return fileSize; }
    public String getChecksum() { return checksum; }
    public FileStatus getStatus() { return status; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public OffsetDateTime getUpdatedAt() { return updatedAt; }
}
