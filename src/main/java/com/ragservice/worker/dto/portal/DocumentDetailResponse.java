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
import java.util.List;

public record DocumentDetailResponse(
        String documentId,
        String fileId,
        String categoryId,
        UserType userType,
        String userNo,
        String title,
        String originalFileName,
        long fileSize,
        String checksum,
        FileStatus fileStatus,
        ProcessingDisplayStatus processingStatus,
        int progressPercent,
        boolean terminal,
        JobSummary latestJob,
        List<JobSummary> jobs,
        String categoryName,
        OffsetDateTime createdAt
) {
    public record JobSummary(
            String jobId,
            RagJobStatus status,
            RagJobStep currentStep,
            String triggerType,
            String errorCode,
            String errorMessage,
            OffsetDateTime startedAt,
            OffsetDateTime endedAt,
            OffsetDateTime createdAt
    ) {
        public static JobSummary of(RagDocumentJob job, RagDocumentFile file) {
            return new JobSummary(
                    job.getId().toString(),
                    job.getStatus(),
                    job.getCurrentStep(),
                    job.getTriggerType().name(),
                    DocumentProcessingResolver.exposedErrorCode(file, job),
                    DocumentProcessingResolver.exposedErrorMessage(file, job),
                    job.getStartedAt(),
                    job.getEndedAt(),
                    job.getCreatedAt()
            );
        }
    }

    public static DocumentDetailResponse of(
            RagDocument doc,
            RagDocumentFile file,
            List<RagDocumentJob> jobs,
            String categoryName
    ) {
        List<JobSummary> jobSummaries = jobs.stream().map(j -> JobSummary.of(j, file)).toList();
        JobSummary latest = jobSummaries.isEmpty() ? null : jobSummaries.get(0);
        RagDocumentJob latestJob = jobs.isEmpty() ? null : jobs.get(0);
        return new DocumentDetailResponse(
                doc.getId().toString(),
                file.getId().toString(),
                doc.getCategoryId() != null ? doc.getCategoryId().toString() : null,
                doc.getUserType(),
                doc.getUserNo().toString(),
                file.getTitle(),
                file.getOriginalFileName(),
                file.getFileSize() != null ? file.getFileSize() : 0L,
                file.getChecksum(),
                file.getStatus(),
                DocumentProcessingResolver.resolve(file, latestJob),
                DocumentProcessingResolver.progressPercent(latestJob),
                DocumentProcessingResolver.isTerminal(file, latestJob),
                latest,
                jobSummaries,
                categoryName,
                doc.getCreatedAt()
        );
    }
}
