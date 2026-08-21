package com.ragservice.worker.dto.pipeline;

import com.ragservice.worker.domain.RagDocumentJob;
import com.ragservice.worker.domain.RagEmbeddingPart;
import com.ragservice.worker.domain.enums.RagEmbeddingPartStatus;
import com.ragservice.worker.domain.enums.RagJobStatus;
import com.ragservice.worker.domain.enums.RagJobStep;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Collections;

public record JobDetailResponse(
        String jobId,
        String documentId,
        String transactionId,
        RagJobStatus status,
        RagJobStep currentStep,
        String triggerType,
        String errorCode,
        String errorMessage,
        int progressPercent,
        EmbeddingStats embeddingStats,
        List<StepTimingEntry> stepTimings,
        OffsetDateTime startedAt,
        OffsetDateTime endedAt,
        OffsetDateTime createdAt
) {
    public record EmbeddingStats(
            long totalParts,
            long upsertedParts,
            long failedParts,
            long totalPoints
    ) {
        public static EmbeddingStats of(List<RagEmbeddingPart> parts) {
            long total = parts.size();
            long upserted = parts.stream().filter(p -> p.getStatus() == RagEmbeddingPartStatus.UPSERTED).count();
            long failed = parts.stream().filter(p -> p.getStatus() == RagEmbeddingPartStatus.FAILED).count();
            long points = parts.stream()
                    .filter(p -> p.getPointCount() != null)
                    .mapToLong(RagEmbeddingPart::getPointCount)
                    .sum();
            return new EmbeddingStats(total, upserted, failed, points);
        }
    }

    public static JobDetailResponse of(RagDocumentJob job, List<RagEmbeddingPart> embeddingParts,
                                       List<StepTimingEntry> stepTimings) {
        JobStatusResponse status = JobStatusResponse.of(job);
        EmbeddingStats stats = embeddingParts != null ? EmbeddingStats.of(embeddingParts) : null;
        return new JobDetailResponse(
                job.getId().toString(),
                job.getDocumentId().toString(),
                job.getTransactionId() != null ? job.getTransactionId().toString() : null,
                job.getStatus(),
                job.getCurrentStep(),
                job.getTriggerType().name(),
                job.getErrorCode(),
                job.getErrorMessage(),
                status.progressPercent(),
                stats,
                stepTimings != null ? stepTimings : Collections.emptyList(),
                job.getStartedAt(),
                job.getEndedAt(),
                job.getCreatedAt()
        );
    }
}
