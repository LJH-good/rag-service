package com.init.worker.repo;

import com.init.worker.domain.RagDocumentJob;
import com.init.worker.domain.enums.RagJobStatus;
import com.init.worker.domain.enums.RagJobStep;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

public interface RagDocumentJobRepositoryCustom {

    boolean existsActiveJobByUserNo(UUID userNo);

    /** 문서에 PENDING/RUNNING job 이 있으면 true (REINDEX 충돌 검사용). */
    boolean existsActiveJobByDocumentId(UUID documentId);

    /**
     * 특정 step 대기열에서 RUNNING/PENDING 후보를 가져오며, PESSIMISTIC_WRITE 잠금을 건다.
     */
    java.util.List<RagDocumentJob> findNextStepQueueForUpdate(RagJobStep step, Pageable pageable);

    /**
     * PENDING + currentStep=null 우선순위 후보를 가져오며, PESSIMISTIC_WRITE 잠금을 건다.
     */
    java.util.List<RagDocumentJob> findNextPendingForUpdate(Pageable pageable);

    java.util.List<RagDocumentJob> findByDocumentId(UUID documentId);

    Page<RagDocumentJob> findByDocumentIdPaged(UUID documentId, Pageable pageable);

    java.util.Optional<RagDocumentJob> findLatestByTransactionId(UUID transactionId);

    long countByStatusAndStep(RagJobStatus status, RagJobStep step);

    /**
     * {@code status=RUNNING} 이면서 {@code startedAt} 이 cutoff 이전인 job (좀비 복구 대상).
     */
    List<RagDocumentJob> findStuckRunningJobsOlderThan(OffsetDateTime cutoff, Pageable pageable);

    /** {@code RUNNING} 일 때만 다음 단계 대기(PENDING)로 전이. 성공 시 true. */
    boolean transitionRunningToNextStep(UUID jobId, RagJobStep nextStep);

    /** {@code RUNNING} 일 때만 terminal SUCCEEDED 로 전이. 성공 시 true. */
    boolean transitionRunningToSucceeded(UUID jobId);

    /** {@code RUNNING} 일 때만 terminal FAILED 로 전이. 성공 시 true. */
    boolean transitionRunningToFailed(UUID jobId, String errorCode, String errorMessage);

    /** {@code RUNNING} 일 때만 동일 step 재대기(PENDING). 성공 시 true. */
    boolean transitionRunningToRequeue(UUID jobId);

    /** {@code RUNNING} 일 때만 PCC 재시작(PENDING, step=null). 성공 시 true. */
    boolean transitionRunningToPccReset(UUID jobId);
}
