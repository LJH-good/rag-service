package com.init.worker.dto.admin;

import com.init.worker.domain.enums.FileStatus;
import com.init.worker.domain.enums.RagEmbeddingPartStatus;
import com.init.worker.domain.enums.RagJobStatus;
import com.init.worker.domain.enums.RagJobStep;
import com.init.worker.domain.enums.UserType;
import com.init.worker.dto.pipeline.StepTimingEntry;
import java.time.OffsetDateTime;
import java.util.List;

public record DataSourceProcessingDetailResponse(
        String documentId,
        String fileId,
        String categoryId,
        String categoryName,
        UserType userType,
        String userNo,
        String title,
        String originalFileName,
        long fileSize,
        String checksum,
        FileStatus fileStatus,
        PipelineDetail pipeline,
        List<JobInfo> jobs,
        List<ChunkInfo> chunks,
        List<StepTimingEntry> stepTimings,
        OffsetDateTime createdAt
) {
    public record PipelineDetail(
            RagJobStatus status,
            RagJobStep currentStep,
            RagJobStep failedStep,
            String errorCode,
            String errorMessage,
            int progressPercent,
            long totalChunks,
            long totalParts,
            long readyParts,
            long upsertedParts,
            long failedParts,
            long totalPoints
    ) {}

    public record JobInfo(
            String jobId,
            RagJobStatus status,
            RagJobStep currentStep,
            String triggerType,
            String errorCode,
            String errorMessage,
            OffsetDateTime startedAt,
            OffsetDateTime endedAt,
            OffsetDateTime createdAt
    ) {}

    public record ChunkInfo(
            String chunkId,
            int chunkIndex,
            Integer charCount,
            String storageKey,
            RagEmbeddingPartStatus embeddingStatus,
            Long pointCount,
            String textPreview
    ) {}

}
