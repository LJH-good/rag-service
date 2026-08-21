package com.ragservice.worker.worker.scheduler;

import com.ragservice.worker.service.RagStuckJobRecoveryService;
import com.ragservice.worker.worker.runner.GroupWorkerRunner;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Consumer에서 {@link GroupWorkerRunner}를 주기적으로 1회 실행한다.
 *
 * <p>{@code rag.app.role=consumer} 일 때만 동작한다.
 */
@Component
@RequiredArgsConstructor
@Slf4j
@ConditionalOnProperty(name = "rag.app.role", havingValue = "consumer")
public class RagGroupWorkerScheduler {

    private final GroupWorkerRunner workerRunner;
    private final RagStuckJobRecoveryService stuckJobRecoveryService;
    private long tickCount = 0;

    @Scheduled(fixedDelayString = "${rag.worker.delay-ms:1000}")
    public void tick() {
        tickCount++;
        if (tickCount == 1 || tickCount % 30 == 0) {
            stuckJobRecoveryService.recoverStuckJobs();
        }
        // Debugging 용: consumer 프로세스가 실제로 tick을 돌고 있는지 확인한다.
        if (tickCount % 30 == 1) {
            log.debug("[RAG_CONSUMER] tick start. tickCount={}", tickCount);
        }
        workerRunner.runOnce();
    }
}
