package com.init.worker.service;

import com.init.worker.domain.RagDocumentFile;
import com.init.worker.domain.RagDocumentJob;
import com.init.worker.domain.enums.FileStatus;
import com.init.worker.domain.enums.ProcessingDisplayStatus;
import com.init.worker.domain.enums.RagJobStatus;
import com.init.worker.dto.pipeline.JobStatusResponse;

/**
 * 문서·Job raw 상태를 UI 표시용 상태로 정규화한다.
 */
public final class DocumentProcessingResolver {

    private DocumentProcessingResolver() {}

    public static ProcessingDisplayStatus resolve(RagDocumentFile file, RagDocumentJob job) {
        // job terminal 상태가 file 레거시(INDEXED/FAILED)보다 우선한다.
        if (job != null) {
            if (job.getStatus() == RagJobStatus.FAILED) {
                return ProcessingDisplayStatus.FAILED;
            }
            if (job.getStatus() == RagJobStatus.SUCCEEDED) {
                return ProcessingDisplayStatus.SUCCEEDED;
            }
            if (isPipelineActive(job)) {
                return ProcessingDisplayStatus.PROCESSING;
            }
        }
        if (file != null && file.getStatus() == FileStatus.INDEXED) {
            return ProcessingDisplayStatus.SUCCEEDED;
        }
        if (file != null && file.getStatus() == FileStatus.FAILED) {
            return ProcessingDisplayStatus.FAILED;
        }
        return ProcessingDisplayStatus.PROCESSING;
    }

    private static boolean isPipelineActive(RagDocumentJob job) {
        return job.getStatus() == RagJobStatus.PENDING || job.getStatus() == RagJobStatus.RUNNING;
    }

    public static ProcessingDisplayStatus resolveJobOnly(RagDocumentJob job) {
        if (job == null) {
            return ProcessingDisplayStatus.PROCESSING;
        }
        if (job.getStatus() == RagJobStatus.SUCCEEDED) {
            return ProcessingDisplayStatus.SUCCEEDED;
        }
        if (isPipelineActive(job)) {
            return ProcessingDisplayStatus.PROCESSING;
        }
        if (job.getStatus() == RagJobStatus.FAILED) {
            return ProcessingDisplayStatus.FAILED;
        }
        return ProcessingDisplayStatus.PROCESSING;
    }

    public static boolean isTerminal(RagDocumentFile file, RagDocumentJob job) {
        ProcessingDisplayStatus status = resolve(file, job);
        return status == ProcessingDisplayStatus.SUCCEEDED || status == ProcessingDisplayStatus.FAILED;
    }

    public static int progressPercent(RagDocumentJob job) {
        return job != null ? JobStatusResponse.of(job).progressPercent() : 5;
    }

    /** 확정 실패가 아니면 Job 오류 필드를 API에 내리지 않는다 (처리 중 임베딩 오류 토스트 방지). */
    public static String exposedErrorCode(RagDocumentFile file, RagDocumentJob job) {
        if (job == null || resolve(file, job) != ProcessingDisplayStatus.FAILED) {
            return null;
        }
        return job.getErrorCode();
    }

    public static String exposedErrorMessage(RagDocumentFile file, RagDocumentJob job) {
        if (job == null || resolve(file, job) != ProcessingDisplayStatus.FAILED) {
            return null;
        }
        return job.getErrorMessage();
    }

    public static String exposedErrorCode(RagDocumentJob job) {
        if (job == null || resolveJobOnly(job) != ProcessingDisplayStatus.FAILED) {
            return null;
        }
        return job.getErrorCode();
    }

    public static String exposedErrorMessage(RagDocumentJob job) {
        if (job == null || resolveJobOnly(job) != ProcessingDisplayStatus.FAILED) {
            return null;
        }
        return job.getErrorMessage();
    }
}
