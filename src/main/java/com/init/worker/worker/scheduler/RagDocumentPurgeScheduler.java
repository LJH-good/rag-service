package com.init.worker.worker.scheduler;

import com.init.worker.service.RagDocumentPurgeService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * soft-delete 후 보관 기간(기본 100일)이 지난 문서의 스토리지·VectorDB·Graph RAG 데이터를 주기적으로 제거한다.
 */
@Component
@RequiredArgsConstructor
@Slf4j
@ConditionalOnProperty(name = "rag.purge.enabled", havingValue = "true")
public class RagDocumentPurgeScheduler {

    private final RagDocumentPurgeService purgeService;

    @Scheduled(cron = "${rag.purge.cron:0 0 3 * * *}")
    public void purgeExpiredDocuments() {
        log.debug("[PURGE_SCHEDULER] run start");
        int count = purgeService.purgeExpired();
        log.debug("[PURGE_SCHEDULER] run end. purged={}", count);
    }
}
