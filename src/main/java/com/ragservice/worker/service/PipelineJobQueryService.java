package com.ragservice.worker.service;

import com.ragservice.worker.domain.RagDocumentJob;
import com.ragservice.worker.domain.RagJobStepTiming;
import com.ragservice.worker.domain.enums.RagStepTimingStatus;
import com.ragservice.worker.dto.common.PagedResponse;
import com.ragservice.worker.dto.pipeline.JobDetailResponse;
import com.ragservice.worker.dto.pipeline.JobStatusResponse;
import com.ragservice.worker.dto.pipeline.StepTimingEntry;
import com.ragservice.worker.error.code.ErrorCodes;
import com.ragservice.worker.error.exception.AppException;
import com.ragservice.worker.repo.RagDocumentJobRepository;
import com.ragservice.worker.repo.RagEmbeddingPartRepository;
import com.ragservice.worker.repo.RagJobStepTimingRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
@Slf4j
public class PipelineJobQueryService {

    private final RagDocumentJobRepository jobRepo;
    private final RagEmbeddingPartRepository embeddingPartRepo;
    private final RagJobStepTimingRepository stepTimingRepo;

    public JobStatusResponse getJobStatus(UUID jobId) {
        RagDocumentJob job = jobRepo.findById(jobId)
                .orElseThrow(() -> new AppException(ErrorCodes.Api.JOB_NOT_FOUND,
                        Map.of("jobId", jobId)));
        JobStatusResponse response = JobStatusResponse.of(job);
        // 포털 폴링(~2s) 경로 — INFO면 RUNNING 동안 동일 로그가 연속으로 쌓임
        log.debug("[RAG_JOB_QUERY] byJobId={} -> status={} step={} processingStatus={} terminal={} errorCode={} errorMessage={}",
                jobId,
                response.status(),
                response.currentStep(),
                response.processingStatus(),
                response.terminal(),
                response.errorCode(),
                response.errorMessage());
        return response;
    }

    public JobStatusResponse getLatestJobStatusByTransaction(UUID transactionId) {
        RagDocumentJob job = jobRepo.findLatestByTransactionId(transactionId)
                .orElseThrow(() -> new AppException(ErrorCodes.Api.JOB_NOT_FOUND,
                        Map.of("transactionId", transactionId)));
        JobStatusResponse response = JobStatusResponse.of(job);
        log.debug("[RAG_JOB_QUERY] byTransactionId={} -> jobId={} status={} step={} processingStatus={} terminal={} errorCode={} errorMessage={}",
                transactionId,
                response.jobId(),
                response.status(),
                response.currentStep(),
                response.processingStatus(),
                response.terminal(),
                response.errorCode(),
                response.errorMessage());
        return response;
    }

    public JobDetailResponse getJobDetail(UUID jobId) {
        RagDocumentJob job = jobRepo.findById(jobId)
                .orElseThrow(() -> new AppException(ErrorCodes.Api.JOB_NOT_FOUND,
                        Map.of("jobId", jobId)));
        var parts = embeddingPartRepo.findPartsByDocumentId(job.getDocumentId());
        var rawTimings = stepTimingRepo.findByJobIdOrderByStartedAtAsc(jobId);
        var stepTimings = buildStepTimings(job, rawTimings);
        JobDetailResponse detail = JobDetailResponse.of(job, parts, stepTimings);
        log.debug("[RAG_JOB_QUERY] detail jobId={} status={} step={} errorCode={} parts(total={}, ready={}, upserted={}, failed={})",
                jobId,
                detail.status(),
                detail.currentStep(),
                detail.errorCode(),
                parts.size(),
                detail.embeddingStats() != null
                        ? (detail.embeddingStats().totalParts()
                        - detail.embeddingStats().upsertedParts()
                        - detail.embeddingStats().failedParts()) : 0,
                detail.embeddingStats() != null ? detail.embeddingStats().upsertedParts() : 0,
                detail.embeddingStats() != null ? detail.embeddingStats().failedParts() : 0);
        return detail;
    }

    /**
     * 스테이지별 처리 시간과 스테이지 간 대기 시간(폴링 지연)을 계산한다.
     * ABANDONED 항목(비정상 종료 후 정리된 행)은 제외하고 시간순으로 정렬한다.
     * waitBeforeMs: 첫 번째 스테이지는 Job 생성 시각 기준, 이후는 직전 스테이지 종료 시각 기준.
     */
    private List<StepTimingEntry> buildStepTimings(RagDocumentJob job, List<RagJobStepTiming> rawTimings) {
        List<RagJobStepTiming> meaningful = rawTimings.stream()
                .filter(t -> t.getStatus() != RagStepTimingStatus.ABANDONED)
                .toList(); // already ordered by startedAt ASC from the query

        List<StepTimingEntry> result = new ArrayList<>(meaningful.size());
        OffsetDateTime previousEndedAt = job.getCreatedAt();

        for (RagJobStepTiming timing : meaningful) {
            Long waitBeforeMs = null;
            if (previousEndedAt != null && timing.getStartedAt() != null) {
                long gap = Duration.between(previousEndedAt, timing.getStartedAt()).toMillis();
                waitBeforeMs = Math.max(0, gap);
            }

            result.add(new StepTimingEntry(
                    timing.getStep(),
                    timing.getStatus(),
                    timing.getAttempt(),
                    timing.getDurationMs(),
                    waitBeforeMs,
                    timing.getErrorCode(),
                    timing.getStartedAt(),
                    timing.getEndedAt()
            ));

            if (timing.getEndedAt() != null) {
                previousEndedAt = timing.getEndedAt();
            }
        }

        return result;
    }

    public PagedResponse<JobStatusResponse> listJobsByDocument(UUID documentId, int page, int size) {
        Page<RagDocumentJob> paged = jobRepo.findByDocumentIdPaged(documentId, PageRequest.of(page, size));
        return PagedResponse.of(paged.map(JobStatusResponse::of));
    }

    public List<JobStatusResponse> listAllJobsByDocument(UUID documentId) {
        return jobRepo.findByDocumentId(documentId).stream()
                .map(JobStatusResponse::of)
                .toList();
    }
}
