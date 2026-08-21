package com.ragservice.worker.repo;

import com.ragservice.worker.domain.RagJobStepTiming;
import com.ragservice.worker.domain.enums.RagJobStep;
import com.ragservice.worker.domain.enums.RagStepTimingStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

/**
 * rag_job_step_timings 테이블 접근 Repository.
 */
public interface RagJobStepTimingRepository extends JpaRepository<RagJobStepTiming, UUID> {

    /** 아직 종료되지 않은(ended_at NULL) 행 — 최근 시작 순. 정상적으로는 job 당 최대 1건. */
    List<RagJobStepTiming> findByJobIdAndEndedAtIsNullOrderByStartedAtDesc(UUID jobId);

    /** 재시도 attempt 번호 계산용 — ABANDONED(placeholder 정리)는 제외. */
    long countByJobIdAndStepAndStatusNot(UUID jobId, RagJobStep step, RagStepTimingStatus status);

    /** 문서의 모든 단계 타이밍 — 시작 시각 오름차순. */
    List<RagJobStepTiming> findByDocumentIdOrderByStartedAtAsc(UUID documentId);

    /** 특정 Job의 모든 단계 타이밍 — 시작 시각 오름차순. */
    List<RagJobStepTiming> findByJobIdOrderByStartedAtAsc(UUID jobId);
}
