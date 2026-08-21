package com.ragservice.worker.dto.portal;

import com.ragservice.worker.domain.RagDocument;
import com.ragservice.worker.domain.RagDocumentFile;
import com.ragservice.worker.domain.RagDocumentJob;
import com.ragservice.worker.domain.enums.FileStatus;
import com.ragservice.worker.domain.enums.ProcessingDisplayStatus;
import com.ragservice.worker.domain.enums.RagJobStatus;
import com.ragservice.worker.domain.enums.RagJobStep;
import com.ragservice.worker.domain.enums.UserType;
import com.ragservice.worker.service.DocumentProcessingResolver;
import java.time.OffsetDateTime;

public record DocumentListItem(
        String documentId,
        String fileId,
        String categoryId,
        UserType userType,
        String userNo,
        String title,
        String originalFileName,
        long fileSize,
        FileStatus fileStatus,
        RagJobStatus jobStatus,
        RagJobStep currentStep,
        String jobId,
        ProcessingDisplayStatus processingStatus,
        int progressPercent,
        boolean terminal,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt
) {
    public static DocumentListItem of(RagDocument doc, RagDocumentFile file, RagDocumentJob latestJob) {
        return new DocumentListItem(
                doc.getId().toString(),
                file.getId().toString(),
                doc.getCategoryId() != null ? doc.getCategoryId().toString() : null,
                doc.getUserType(),
                doc.getUserNo().toString(),
                file.getTitle(),
                file.getOriginalFileName(),
                file.getFileSize() != null ? file.getFileSize() : 0L,
                file.getStatus(),
                latestJob != null ? latestJob.getStatus() : null,
                latestJob != null ? latestJob.getCurrentStep() : null,
                latestJob != null ? latestJob.getId().toString() : null,
                DocumentProcessingResolver.resolve(file, latestJob),
                DocumentProcessingResolver.progressPercent(latestJob),
                DocumentProcessingResolver.isTerminal(file, latestJob),
                doc.getCreatedAt(),
                file.getUpdatedAt()
        );
    }
}
