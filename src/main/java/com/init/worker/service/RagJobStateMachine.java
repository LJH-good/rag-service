package com.init.worker.service;

import com.init.worker.config.RagProperties;
import com.init.worker.domain.RagDocument;
import com.init.worker.domain.RagDocumentFile;
import com.init.worker.domain.RagDocumentJob;
import com.init.worker.domain.enums.RagJobStep;
import com.init.worker.domain.enums.RagStepTimingStatus;
import com.init.worker.repo.RagDocumentFileRepository;
import com.init.worker.repo.RagDocumentJobRepository;
import com.init.worker.repo.RagDocumentRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * RAG 파이프라인 Job 상태 전이.
 * - handle() 장시간 트랜잭션과 분리(REQUIRES_NEW)하고, DB에서 {@code RUNNING} 일 때만 CAS 전이한다.
 * - terminal FAILED 이후 stale success 가 job/file 을 덮어쓰지 않는다.
 * - requeue(재시도) 는 {@link RagJobRetryTracker} 로 횟수를 제한하며, maxRetry 초과 시 terminal FAILED 로 확정한다.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class RagJobStateMachine {

    private static final int DEFAULT_MAX_RETRY = 3;

    private final RagDocumentJobRepository jobRepo;
    private final RagDocumentRepository docRepo;
    private final RagDocumentFileRepository fileRepo;
    private final RagProperties ragProperties;
    private final RagJobRetryTracker retryTracker;
    private final RagJobStepTimingRecorder stepTimingRecorder;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void onStepSuccess(RagDocumentJob job, RagJobStep nextStepOrNull) {
        UUID jobId = job.getId();
        if (nextStepOrNull != null) {
            if (!jobRepo.transitionRunningToNextStep(jobId, nextStepOrNull)) {
                log.warn("[RAG_SM] ignore step success — job not RUNNING (CAS miss). jobId={}, nextStep={}",
                        jobId, nextStepOrNull);
                return;
            }
            // 단계 진행은 진척이므로 재시도 카운트를 리셋한다.
            retryTracker.reset(jobId);
            stepTimingRecorder.end(jobId, RagStepTimingStatus.SUCCEEDED, null);
            log.info("[RAG_SM][{}] step success -> PENDING({})", jobId, nextStepOrNull);
            return;
        }

        if (!jobRepo.transitionRunningToSucceeded(jobId)) {
            log.warn("[RAG_SM] ignore terminal success — job not RUNNING (CAS miss). jobId={}", jobId);
            return;
        }
        retryTracker.reset(jobId);
        stepTimingRecorder.end(jobId, RagStepTimingStatus.SUCCEEDED, null);
        log.info("[RAG_SM][{}] job SUCCEEDED (terminal)", jobId);
        markFileIndexed(jobId);
    }

    private void markFileIndexed(UUID jobId) {
        jobRepo.findById(jobId).ifPresent(current ->
            docRepo.findById(current.getDocumentId()).ifPresent(doc ->
                fileRepo.findById(doc.getFileId()).ifPresent(file -> {
                    file.markIndexed();
                    fileRepo.save(file);
                    log.info("[RAG_SM][{}] file INDEXED on job success. fileId={}", jobId, file.getId());
                })
            )
        );
    }

    /**
     * UPSERT(벡터 RAG) 성공 처리. 벡터 색인이 끝났으므로 파일을 INDEXED 로 확정한다.
     * <ul>
     *   <li>{@code toGraph=true}: Pass2(EXTRACT_RELATION) 대기열로 전이(job 은 계속 진행).</li>
     *   <li>{@code toGraph=false}: terminal SUCCEEDED.</li>
     * </ul>
     * 어느 쪽이든 파일은 INDEXED — 이후 Pass2 실패가 파일 상태를 강등하지 않는다(비차단).
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void onUpsertSuccess(RagDocumentJob job, boolean toGraph) {
        UUID jobId = job.getId();
        boolean transitioned = toGraph
                ? jobRepo.transitionRunningToNextStep(jobId, RagJobStep.EXTRACT_RELATION)
                : jobRepo.transitionRunningToSucceeded(jobId);
        if (!transitioned) {
            log.warn("[RAG_SM] ignore upsert success — job not RUNNING (CAS miss). jobId={}, toGraph={}",
                    jobId, toGraph);
            return;
        }
        retryTracker.reset(jobId);
        stepTimingRecorder.end(jobId, RagStepTimingStatus.SUCCEEDED, null);
        markFileIndexed(jobId);
        log.info("[RAG_SM][{}] UPSERT success — file INDEXED. next={}",
                jobId, toGraph ? "EXTRACT_RELATION" : "SUCCEEDED(terminal)");
    }

    /**
     * Pass2(그래프) 종료 처리 — best-effort. 관계 추출을 건너뛰고 job 을 terminal SUCCEEDED 로 확정한다.
     * 벡터 RAG 는 UPSERT 에서 이미 완료·INDEXED 이므로 파일 상태는 건드리지 않는다
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void onGraphSkip(RagDocumentJob job, String code, String message, Throwable e) {
        UUID jobId = job.getId();
        if (!jobRepo.transitionRunningToSucceeded(jobId)) {
            log.warn("[RAG_SM] ignore graph skip — job not RUNNING (CAS miss). jobId={}, code={}", jobId, code);
            return;
        }
        retryTracker.reset(jobId);
        stepTimingRecorder.end(jobId, RagStepTimingStatus.SUCCEEDED, code);
        log.warn("[RAG_SM_GRAPH] graph extraction skipped -> SUCCEEDED (vector RAG intact). jobId={}, code={}, msg={}",
                jobId, code, message, e);
    }

    /**
     * Pass2 일시 장애 requeue. maxRetry 초과 시 {@link #onGraphSkip} 로 넘겨 doc 이 FAILED 로 막히지 않게 한다.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void onGraphTransientRequeue(RagDocumentJob job, String code, String message, Throwable e) {
        UUID jobId = job.getId();
        int attempt = retryTracker.increment(jobId);
        int maxRetry = resolveMaxRetry();
        if (attempt > maxRetry) {
            if (jobRepo.transitionRunningToSucceeded(jobId)) {
                retryTracker.reset(jobId);
                stepTimingRecorder.end(jobId, RagStepTimingStatus.SUCCEEDED, code);
                log.warn("[RAG_SM_GRAPH] graph retry exhausted -> SUCCEEDED (vector RAG intact). jobId={}, attempts={}, code={}, msg={}",
                        jobId, attempt, code, message, e);
            }
            return;
        }
        if (!jobRepo.transitionRunningToRequeue(jobId)) {
            log.warn("[RAG_SM] ignore graph requeue — job not RUNNING (CAS miss). jobId={}, code={}", jobId, code);
            return;
        }
        stepTimingRecorder.end(jobId, RagStepTimingStatus.REQUEUED, code);
        log.warn("[RAG_SM_GRAPH] graph transient failure -> requeued ({}/{}). jobId={}, code={}, msg={}",
                attempt, maxRetry, jobId, code, message, e);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void onStepFailure(RagDocumentJob job,
                              RagDocument doc,
                              String code,
                              String message,
                              Exception e) {
        UUID jobId = job.getId();
        if (!jobRepo.transitionRunningToFailed(jobId, code, message)) {
            log.warn("[RAG_SM] ignore step failure — job not RUNNING (CAS miss). jobId={}, code={}",
                    jobId, code);
            return;
        }
        retryTracker.reset(jobId);
        stepTimingRecorder.end(jobId, RagStepTimingStatus.FAILED, code);
        fileRepo.findById(doc.getFileId()).ifPresent(file -> {
            file.markFailed();
            fileRepo.save(file);
            log.warn("[RAG_SM_FAIL] file FAILED on terminal job failure. jobId={}, docId={}, fileId={}, errorCode={}",
                    jobId, job.getDocumentId(), file.getId(), code);
        });
        log.warn("[RAG_SM_FAIL] job FAILED (terminal). jobId={}, docId={}, errorCode={}, msg={}",
                jobId, job.getDocumentId(), code, message, e);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void onStepFailureWithoutDoc(RagDocumentJob job, String code, String message, Exception e) {
        UUID jobId = job.getId();
        if (!jobRepo.transitionRunningToFailed(jobId, code, message)) {
            log.warn("[RAG_SM] ignore step failure (no-doc) — job not RUNNING (CAS miss). jobId={}, code={}",
                    jobId, code);
            return;
        }
        retryTracker.reset(jobId);
        stepTimingRecorder.end(jobId, RagStepTimingStatus.FAILED, code);
        markFileFailed(jobId, code);
        log.warn("[RAG_SM_FAIL] job FAILED (terminal, no-doc). jobId={}, errorCode={}, msg={}",
                jobId, code, message, e);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void onPccRetryReset(RagDocumentJob job, RagDocument doc) {
        UUID jobId = job.getId();
        if (!jobRepo.transitionRunningToPccReset(jobId)) {
            log.warn("[RAG_SM] ignore PCC retry reset — job not RUNNING (CAS miss). jobId={}", jobId);
            return;
        }
        // 처음부터 다시 타는 리셋이므로 재시도 카운트도 초기화한다.
        retryTracker.reset(jobId);
        stepTimingRecorder.end(jobId, RagStepTimingStatus.REQUEUED, "PCC_RETRY_RESET");
        log.info("[RAG_SM][{}] reset job for PCC retry. docId={}", jobId, doc.getId());
    }

    /**
     * 일시 장애 requeue. maxRetry 초과 시 terminal FAILED 로 확정해 무한 재시도를 막는다.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void onTransientFailureRequeue(RagDocumentJob job, String code, String message, Exception e) {
        UUID jobId = job.getId();
        int attempt = retryTracker.increment(jobId);
        int maxRetry = resolveMaxRetry();
        if (attempt > maxRetry) {
            if (terminalFail(jobId, code, "retry exhausted (" + attempt + "/" + maxRetry + "): " + message)) {
                log.warn("[RAG_SM_FAIL] transient retry exhausted -> FAILED (terminal). jobId={}, attempts={}, code={}, msg={}",
                        jobId, attempt, code, message, e);
            }
            return;
        }
        if (!jobRepo.transitionRunningToRequeue(jobId)) {
            log.warn("[RAG_SM] ignore transient requeue — job not RUNNING (CAS miss). jobId={}, code={}",
                    jobId, code);
            return;
        }
        stepTimingRecorder.end(jobId, RagStepTimingStatus.REQUEUED, code);
        log.warn("[RAG_SM_REQUEUE] transient failure -> requeued ({}/{}). jobId={}, docId={}, code={}, msg={}",
                attempt, maxRetry, jobId, job.getDocumentId(), code, message, e);
    }

    /**
     * timeout 으로 RUNNING 에 잔류한 고아 job 을 PENDING 으로 되돌려 자동 재개한다.
     * maxRetry 초과 시에는 terminal FAILED 로 확정한다.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void onOrphanRecovery(RagDocumentJob job, String code, String message) {
        UUID jobId = job.getId();
        int attempt = retryTracker.increment(jobId);
        int maxRetry = resolveMaxRetry();
        if (attempt > maxRetry) {
            if (terminalFail(jobId, code, "retry exhausted (" + attempt + "/" + maxRetry + "): " + message)) {
                log.warn("[RAG_SM_FAIL] stuck orphan retry exhausted -> FAILED (terminal). jobId={}, attempts={}, msg={}",
                        jobId, attempt, message);
            }
            return;
        }
        if (!jobRepo.transitionRunningToRequeue(jobId)) {
            log.warn("[RAG_SM] ignore orphan requeue — job not RUNNING (CAS miss). jobId={}", jobId);
            return;
        }
        stepTimingRecorder.end(jobId, RagStepTimingStatus.REQUEUED, code);
        log.warn("[RAG_SM_REQUEUE] stuck orphan -> requeued ({}/{}). jobId={}, docId={}, msg={}",
                attempt, maxRetry, jobId, job.getDocumentId(), message);
    }

    /** RUNNING → FAILED 확정 + file FAILED 표시 + 카운트 리셋. 전이 성공 시 true. */
    private boolean terminalFail(UUID jobId, String code, String message) {
        if (!jobRepo.transitionRunningToFailed(jobId, code, message)) {
            log.warn("[RAG_SM] ignore terminal fail — job not RUNNING (CAS miss). jobId={}, code={}", jobId, code);
            return false;
        }
        retryTracker.reset(jobId);
        stepTimingRecorder.end(jobId, RagStepTimingStatus.FAILED, code);
        markFileFailed(jobId, code);
        return true;
    }

    private void markFileFailed(UUID jobId, String code) {
        jobRepo.findById(jobId).ifPresent(current ->
            docRepo.findById(current.getDocumentId()).ifPresent(doc ->
                fileRepo.findById(doc.getFileId()).ifPresent(file -> {
                    file.markFailed();
                    fileRepo.save(file);
                    log.warn("[RAG_SM_FAIL] file FAILED on terminal job failure. jobId={}, fileId={}, errorCode={}",
                            jobId, file.getId(), code);
                })
            )
        );
    }

    private int resolveMaxRetry() {
        if (ragProperties.worker() == null
                || ragProperties.worker().maxRetry() == null
                || ragProperties.worker().maxRetry() < 0) {
            return DEFAULT_MAX_RETRY;
        }
        return ragProperties.worker().maxRetry();
    }
}
