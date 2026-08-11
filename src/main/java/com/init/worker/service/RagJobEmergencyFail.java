package com.init.worker.service;

import com.init.worker.domain.enums.RagJobStatus;
import com.init.worker.repo.RagDocumentJobRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * handle() 트랜잭션이 롤백되어도 job을 FAILED로 확정 커밋하기 위한 긴급 처리 서비스.
 * REQUIRES_NEW 전파로 호출 측 트랜잭션과 완전히 독립된 트랜잭션에서 실행된다.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class RagJobEmergencyFail {

    private final RagDocumentJobRepository jobRepo;
    private final RagJobStateMachine ragJobStateMachine;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markFailed(UUID jobId, Throwable cause) {
        jobRepo.findById(jobId).ifPresent(job -> {
            if (job.getStatus() != RagJobStatus.RUNNING) {
                log.warn("[EMERGENCY_FAIL] job already transitioned to {}, skip. jobId={}",
                        job.getStatus(), jobId);
                return;
            }
            String msg = cause.getMessage() != null
                    ? cause.getMessage()
                    : cause.getClass().getSimpleName();
            ragJobStateMachine.onStepFailureWithoutDoc(job, "UNHANDLED_EXCEPTION", msg, null);
            log.warn("[EMERGENCY_FAIL] job forced FAILED via state machine. jobId={}, cause={}",
                    jobId, msg);
        });
    }
}
