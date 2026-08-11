package com.init.worker.worker.runner;

import com.init.worker.config.RagProperties;
import com.init.worker.worker.RagEmbedWorker;
import com.init.worker.worker.RagGraphEntityWorker;
import com.init.worker.worker.RagGraphWorker;
import com.init.worker.worker.RagPccWorker;
import com.init.worker.worker.RagUpsertWorker;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "rag.app.role", havingValue = "consumer")
@Slf4j
public class ConsumerPipelineRunner implements GroupWorkerRunner {

    private final RagProperties ragProperties;
    private final RagPccWorker pccWorker;
    private final RagGraphEntityWorker entityWorker;
    private final RagEmbedWorker embedWorker;
    private final RagUpsertWorker upsertWorker;
    private final RagGraphWorker graphWorker;
    private final PipelineSteps pipelineSteps;

    public ConsumerPipelineRunner(
            RagProperties ragProperties,
            @Autowired(required = false) RagPccWorker pccWorker,
            @Autowired(required = false) RagGraphEntityWorker entityWorker,
            RagEmbedWorker embedWorker,
            RagUpsertWorker upsertWorker,
            RagGraphWorker graphWorker,
            PipelineSteps pipelineSteps
    ) {
        this.ragProperties = ragProperties;
        this.pccWorker = pccWorker;
        this.entityWorker = entityWorker;
        this.embedWorker = embedWorker;
        this.upsertWorker = upsertWorker;
        this.graphWorker = graphWorker;
        this.pipelineSteps = pipelineSteps;
    }

    @Override
    public void runOnce() {
        // consumer job pickup 흐름이 실제로 도는지 확인하는 디버깅 로그
        log.debug("[RAG_CONSUMER] runOnce start.");
        // graph.enabled 시 진입 단계는 Pass1(EXTRACT_ENTITY)이 PCC 를 대체한다(parse·clean+엔티티+청킹 일괄).
        if (ragProperties.graph().enabled()) {
            pipelineSteps.runEntityPhase(entityWorker);
        } else {
            pipelineSteps.runPccPhase(ragProperties, pccWorker);
        }
        pipelineSteps.runEmbed(embedWorker);
        pipelineSteps.runUpsert(upsertWorker);
        if (ragProperties.graph().enabled()) {
            pipelineSteps.runGraphRelation(graphWorker);
        }
        log.debug("[RAG_CONSUMER] runOnce end.");
    }
}
