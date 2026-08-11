package com.init.worker.domain.enums;

/**
 * rag_job_step_timings 한 행(한 번의 단계 실행 시도)의 종료 상태.
 * <ul>
 *   <li>{@code RUNNING}   — 단계 실행 중 (아직 종료되지 않음, ended_at NULL)</li>
 *   <li>{@code SUCCEEDED} — 단계 성공(다음 단계로 진행 또는 terminal 성공)</li>
 *   <li>{@code FAILED}    — 단계 실패(terminal FAILED)</li>
 *   <li>{@code REQUEUED}  — 일시 장애/재설정으로 재대기(같은 단계 재시도 예정)</li>
 *   <li>{@code ABANDONED} — 이전 시도가 정상 종료되지 못한 채 남아 있던 행을 정리</li>
 * </ul>
 */
public enum RagStepTimingStatus {
    RUNNING,
    SUCCEEDED,
    FAILED,
    REQUEUED,
    ABANDONED
}
