package com.init.worker.dto.pipeline;

import com.init.worker.domain.enums.RagJobStep;
import com.init.worker.domain.enums.RagStepTimingStatus;

import java.time.OffsetDateTime;

/**
 * 파이프라인 단계 한 번의 실행 시도에 대한 타이밍 정보.
 * waitBeforeMs — 이전 단계 종료(또는 Job 생성) 시각부터 이 단계 시작까지의 대기 시간(폴링 지연 포함).
 *               어드민 집계 뷰처럼 대기 시간을 계산하지 않는 경우 null.
 */
public record StepTimingEntry(
        RagJobStep step,
        RagStepTimingStatus status,
        int attempt,
        Long durationMs,
        Long waitBeforeMs,
        String errorCode,
        OffsetDateTime startedAt,
        OffsetDateTime endedAt
) {}
