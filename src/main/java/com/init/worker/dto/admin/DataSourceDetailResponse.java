package com.init.worker.dto.admin;

import com.init.worker.domain.RagDocument;
import com.init.worker.domain.RagDocumentFile;
import com.init.worker.domain.RagDocumentJob;
import com.init.worker.domain.RagIndexMetadata;
import com.init.worker.domain.enums.FileStatus;
import com.init.worker.domain.enums.RagJobStatus;
import com.init.worker.domain.enums.RagJobStep;
import com.init.worker.domain.enums.UserType;
import java.time.OffsetDateTime;
import java.util.List;

public record DataSourceDetailResponse(
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
        IndexInfo indexInfo,
        JobInfo latestJob,
        List<JobInfo> jobs,
        String categoryName,
        OffsetDateTime createdAt
) {
    public record IndexInfo(
            String collection,
            String embeddingModel,
            int embeddingDim,
            OffsetDateTime indexedAt
    ) {
        public static IndexInfo of(RagIndexMetadata m) {
            return new IndexInfo(m.getCollection(), m.getEmbeddingModel(), m.getEmbeddingDim(), m.getIndexedAt());
        }
    }

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
    ) {
        public static JobInfo of(RagDocumentJob job) {
            return new JobInfo(
                    job.getId().toString(),
                    job.getStatus(),
                    job.getCurrentStep(),
                    job.getTriggerType().name(),
                    job.getErrorCode(),
                    job.getErrorMessage(),
                    job.getStartedAt(),
                    job.getEndedAt(),
                    job.getCreatedAt()
            );
        }
    }

    public static DataSourceDetailResponse of(
            RagDocument doc,
            RagDocumentFile file,
            List<RagDocumentJob> jobs,
            RagIndexMetadata indexMeta,
            String categoryName
    ) {
        List<JobInfo> jobInfos = jobs.stream().map(JobInfo::of).toList();
        JobInfo latest = jobInfos.isEmpty() ? null : jobInfos.get(0);
        IndexInfo index = indexMeta != null ? IndexInfo.of(indexMeta) : null;
        return new DataSourceDetailResponse(
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
                index,
                latest,
                jobInfos,
                categoryName,
                doc.getCreatedAt()
        );
    }
}
