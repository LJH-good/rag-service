package com.ragservice.worker.dto;

import java.time.OffsetDateTime;

/**
 * 문서 업로드 API 응답 DTO.
 * - fileId: 생성된 물리 파일 ID (rag_document_files)
 * - documentId: 생성된 문서 ID (rag_documents)
 * - jobId: 파이프라인 처리 Job ID (rag_document_jobs)
 */
public record UploadResponse(
        String documentId,
        String fileId,
        String categoryId,
        String originalFileName,
        long fileSize,
        String jobId,
        String transactionId,
        OffsetDateTime createdAt
) {
    public static UploadResponse of(
            String documentId,
            String fileId,
            String categoryId,
            String originalFileName,
            long fileSize,
            String jobId,
            String transactionId,
            OffsetDateTime createdAt
    ) {
        return new UploadResponse(
                documentId, fileId, categoryId, originalFileName, fileSize, jobId, transactionId, createdAt);
    }
}
