package com.ragservice.worker.service;

import com.ragservice.worker.config.RagProperties;
import com.ragservice.worker.domain.RagDocumentJob;
import com.ragservice.worker.domain.enums.RagJobStep;
import com.ragservice.worker.repo.RagDocumentJobRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * consumer 재시작·크래시 등으로 {@code RUNNING} 에 남은 job 을 timeout 후 {@code PENDING} 으로 복구한다.
 * maxRetry 초과 시에는 terminal {@code FAILED} 로 확정한다.
 */
@Service
@RequiredArgsConstructor
@Slf4j
@ConditionalOnProperty(name = "rag.app.role", havingValue = "consumer")
public class RagStuckJobRecoveryService {

    private static final String ERROR_CODE = "STUCK_RUNNING_TIMEOUT";
    private static final int RECOVERY_BATCH_SIZE = 50;

    private final RagProperties ragProperties;
    private final RagDocumentJobRepository jobRepo;
    private final RagJobStateMachine ragJobStateMachine;

    public int recoverStuckJobs() {
        int timeoutMinutes = resolveTimeoutMinutes();
        if (timeoutMinutes <= 0) {
            return 0;
        }

        OffsetDateTime cutoff = OffsetDateTime.now().minusMinutes(timeoutMinutes);
        List<RagDocumentJob> stuck = jobRepo.findStuckRunningJobsOlderThan(
                cutoff, PageRequest.of(0, RECOVERY_BATCH_SIZE));
        if (stuck.isEmpty()) {
            return 0;
        }

        int recovered = 0;
        for (RagDocumentJob job : stuck) {
            RagJobStep step = job.getCurrentStep();
            String message = "job remained RUNNING past timeout. step="
                    + (step != null ? step : "PCC")
                    + ", startedAt=" + job.getStartedAt()
                    + ", timeoutMinutes=" + timeoutMinutes;
            ragJobStateMachine.onOrphanRecovery(job, ERROR_CODE, message);
            recovered++;
            log.warn("[RAG_STUCK_RECOVERY] requeued stuck job. jobId={} docId={} txId={} step={} startedAt={} timeoutMinutes={}",
                    job.getId(),
                    job.getDocumentId(),
                    job.getTransactionId(),
                    step,
                    job.getStartedAt(),
                    timeoutMinutes);
        }

        log.info("[RAG_STUCK_RECOVERY] processed {} stuck RUNNING job(s). cutoff={}", recovered, cutoff);
        return recovered;
    }

    private int resolveTimeoutMinutes() {
        if (ragProperties.worker() == null || ragProperties.worker().stuckJobTimeoutMinutes() == null) {
            return 15;
        }
        return ragProperties.worker().stuckJobTimeoutMinutes();
    }
}
