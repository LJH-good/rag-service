package com.init.worker.dto.portal;

import com.init.worker.domain.RagDocument;
import com.init.worker.domain.RagDocumentFile;
import com.init.worker.domain.RagDocumentJob;
import com.init.worker.domain.enums.FileStatus;
import com.init.worker.domain.enums.ProcessingDisplayStatus;
import com.init.worker.domain.enums.RagJobStatus;
import com.init.worker.domain.enums.RagJobStep;
import com.init.worker.domain.enums.UserType;
import com.init.worker.service.DocumentProcessingResolver;
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
