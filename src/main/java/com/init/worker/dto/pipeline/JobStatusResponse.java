package com.init.worker.dto.pipeline;

import com.init.worker.domain.RagDocumentJob;
import com.init.worker.domain.enums.ProcessingDisplayStatus;
import com.init.worker.domain.enums.RagJobStatus;
import com.init.worker.domain.enums.RagJobStep;
import com.init.worker.service.DocumentProcessingResolver;
import java.time.OffsetDateTime;

public record JobStatusResponse(
        String jobId,
        String documentId,
        String transactionId,
        RagJobStatus status,
        RagJobStep currentStep,
        String triggerType,
        String errorCode,
        String errorMessage,
        OffsetDateTime startedAt,
        OffsetDateTime endedAt,
        OffsetDateTime createdAt,
        int progressPercent,
        boolean terminal,
        ProcessingDisplayStatus processingStatus
) {
    public static JobStatusResponse of(RagDocumentJob job) {
        RagJobStatus status = job.getStatus();
        RagJobStep currentStep = job.getCurrentStep();
        ProcessingDisplayStatus processingStatus = DocumentProcessingResolver.resolveJobOnly(job);
        boolean terminal = processingStatus == ProcessingDisplayStatus.SUCCEEDED
                || processingStatus == ProcessingDisplayStatus.FAILED;

        return new JobStatusResponse(
                job.getId().toString(),
                job.getDocumentId().toString(),
                job.getTransactionId() != null ? job.getTransactionId().toString() : null,
                status,
                currentStep,
                job.getTriggerType().name(),
                DocumentProcessingResolver.exposedErrorCode(job),
                DocumentProcessingResolver.exposedErrorMessage(job),
                job.getStartedAt(),
                job.getEndedAt(),
                job.getCreatedAt(),
                computeProgressPercent(status, currentStep),
                terminal,
                processingStatus
        );
    }

    /**
     * 진행률 (파이프라인 단계 기준, %). api-spec progressPercent 표와 동일.
     * PENDING 5 · PARSE/PCC 20 · CLEAN 40 · CHUNK 60 · EMBED 75 · UPSERT 90 · SUCCEEDED 100
     */
    private static int computeProgressPercent(RagJobStatus status, RagJobStep currentStep) {
        if (status == RagJobStatus.SUCCEEDED) {
            return 100;
        }
        if (currentStep == null) {
            return 5;
        }
        return switch (currentStep) {
            case PARSE, PCC, EXTRACT_ENTITY -> 20;
            case CLEAN -> 40;
            case CHUNK -> 60;
            case EMBED -> 75;
            case UPSERT -> 90;
            case EXTRACT_RELATION -> 95;
        };
    }
}
