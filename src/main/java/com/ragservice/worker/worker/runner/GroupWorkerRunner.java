package com.ragservice.worker.worker.runner;

/**
 * Consumer 파이프라인을 1회 실행하기 위한 진입점.
 * <p>
 * 현재는 {@link ConsumerPipelineRunner} 구현체만 등록된다.
 */
public interface GroupWorkerRunner {

    void runOnce();
}
