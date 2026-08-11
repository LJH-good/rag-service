package com.init.worker.service;

import org.springframework.stereotype.Component;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * job requeue(재시도) 횟수를 프로세스 메모리에 추적한다.
 *
 * <p>단일 consumer 프로세스 전제로 동작하며, 프로세스 재시작 시 카운트는 초기화된다.
 * (재시작 시점은 고아 job 이 새로 생기는 시점이라 카운트 리셋이 오히려 자연스럽다.)
 */
@Component
public class RagJobRetryTracker {

    private final ConcurrentHashMap<UUID, Integer> attempts = new ConcurrentHashMap<>();

    /** 재시도 횟수를 1 증가시키고 누적값을 반환한다. */
    public int increment(UUID jobId) {
        return attempts.merge(jobId, 1, Integer::sum);
    }

    /** 단계 진행/종료 시 카운트를 비운다. */
    public void reset(UUID jobId) {
        attempts.remove(jobId);
    }
}
