package com.ragservice.worker.worker.runner;

import com.ragservice.worker.config.RagProperties;
import com.ragservice.worker.domain.enums.RagJobStatus;
import com.ragservice.worker.domain.enums.RagJobStep;
import com.ragservice.worker.repo.RagDocumentJobRepository;
import com.ragservice.worker.service.RagJobEmergencyFail;
import com.ragservice.worker.service.RagJobStepTimingRecorder;
import com.ragservice.worker.worker.RagEmbedWorker;
import com.ragservice.worker.worker.RagGraphEntityWorker;
import com.ragservice.worker.worker.RagGraphWorker;
import com.ragservice.worker.worker.RagPccWorker;
import com.ragservice.worker.worker.RagUpsertWorker;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * PCC, EMBED, UPSERT 단계 실행 로직.
 * handle()이 예외를 탈출시켜 트랜잭션이 롤백될 경우 job이 RUNNING으로 잔류하는 것을
 * 방지하기 위해, 각 단계 호출을 try-catch로 감싸고 REQUIRES_NEW 트랜잭션으로 FAILED 확정한다.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class PipelineSteps {

    private final RagJobEmergencyFail emergencyFail;
    private final RagDocumentJobRepository jobRepo;
    private final RagJobStepTimingRecorder stepTimingRecorder;
    private long noPickPccCount = 0;
    private long noPickEntityCount = 0;
    private long noPickEmbedCount = 0;
    private long noPickUpsertCount = 0;
    private long noPickGraphCount = 0;

    /** Graph RAG Pass1(EXTRACT_ENTITY) — graph.enabled 시 PCC 를 대체하는 진입 단계. 초기 PENDING 을 픽업한다. */
    public void runEntityPhase(RagGraphEntityWorker entityWorkerOrNull) {
        if (entityWorkerOrNull == null) {
            log.error("[RAG_ENTITY] RagGraphEntityWorker bean is missing (graph.enabled 인데 consumer 아님?)");
            return;
        }
        entityWorkerOrNull.pick().ifPresentOrElse(job -> {
            stepTimingRecorder.begin(job.getId(), job.getDocumentId(), RagJobStep.EXTRACT_ENTITY);
            try {
                entityWorkerOrNull.handle(job);
            } catch (Throwable t) {
                log.error("[RAG_ENTITY][{}] uncaught exception — emergency fail", job.getId(), t);
                emergencyFail.markFailed(job.getId(), t);
            }
        }, () -> logNoPick("RAG_ENTITY", ++noPickEntityCount, RagJobStatus.PENDING, null, RagJobStatus.RUNNING, null));
    }

    public void runPccPhase(RagProperties props, RagPccWorker pccWorkerOrNull) {
        if (props.pcc() == null || !props.pcc().langchainEnabled()) {
            log.error("[RAG_PCC] rag.pcc.langchain-enabled=true 가 필수입니다.");
            return;
        }
        if (pccWorkerOrNull == null) {
            log.error("[RAG_PCC] RagPccWorker bean is missing");
            return;
        }
        pccWorkerOrNull.pick().ifPresentOrElse(job -> {
            stepTimingRecorder.begin(job.getId(), job.getDocumentId(), RagJobStep.PCC);
            try {
                pccWorkerOrNull.handle(job);
            } catch (Throwable t) {
                log.error("[RAG_PCC][{}] uncaught exception — emergency fail", job.getId(), t);
                emergencyFail.markFailed(job.getId(), t);
            }
        }, () -> logNoPick("RAG_PCC", ++noPickPccCount, RagJobStatus.PENDING, null, RagJobStatus.RUNNING, null));
    }

    public void runEmbed(RagEmbedWorker embedWorker) {
        embedWorker.pick().ifPresentOrElse(job -> {
            stepTimingRecorder.begin(job.getId(), job.getDocumentId(), RagJobStep.EMBED);
            try {
                embedWorker.handle(job);
            } catch (Throwable t) {
                log.error("[RAG_EMBED][{}] uncaught exception — emergency fail", job.getId(), t);
                emergencyFail.markFailed(job.getId(), t);
            }
        }, () -> logNoPick("RAG_EMBED", ++noPickEmbedCount,
                RagJobStatus.PENDING, RagJobStep.EMBED, RagJobStatus.RUNNING, RagJobStep.EMBED));
    }

    public void runUpsert(RagUpsertWorker upsertWorker) {
        upsertWorker.pick().ifPresentOrElse(job -> {
            stepTimingRecorder.begin(job.getId(), job.getDocumentId(), RagJobStep.UPSERT);
            try {
                upsertWorker.handle(job);
            } catch (Throwable t) {
                log.error("[RAG_UPSERT][{}] uncaught exception — emergency fail", job.getId(), t);
                emergencyFail.markFailed(job.getId(), t);
            }
        }, () -> logNoPick("RAG_UPSERT", ++noPickUpsertCount,
                RagJobStatus.PENDING, RagJobStep.UPSERT, RagJobStatus.RUNNING, RagJobStep.UPSERT));
    }

    public void runGraphRelation(RagGraphWorker graphWorker) {
        graphWorker.pick().ifPresentOrElse(job -> {
            stepTimingRecorder.begin(job.getId(), job.getDocumentId(), RagJobStep.EXTRACT_RELATION);
            try {
                graphWorker.handle(job);
            } catch (Throwable t) {
                log.error("[RAG_GRAPH][{}] uncaught exception — emergency fail", job.getId(), t);
                emergencyFail.markFailed(job.getId(), t);
            }
        }, () -> logNoPick("RAG_GRAPH", ++noPickGraphCount,
                RagJobStatus.PENDING, RagJobStep.EXTRACT_RELATION,
                RagJobStatus.RUNNING, RagJobStep.EXTRACT_RELATION));
    }

    /** 유휴 하트비트 — 단계마다 ~30s INFO로 쌓이므로 DEBUG. claim은 {@code RAG_PICK} INFO로 확인. */
    private void logNoPick(
            String prefix,
            long count,
            RagJobStatus pendingStatus,
            RagJobStep pendingStep,
            RagJobStatus runningStatus,
            RagJobStep runningStep
    ) {
        if (count % 30 != 1 || !log.isDebugEnabled()) {
            return;
        }
        long pending = jobRepo.countByStatusAndStep(pendingStatus, pendingStep);
        long running = jobRepo.countByStatusAndStep(runningStatus, runningStep);
        log.debug("[{}] no pending job to pick. count={} queueSnapshot={{pending={}, running={}}}",
                prefix, count, pending, running);
    }
}
