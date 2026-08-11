package com.init.worker.domain;

import com.init.worker.domain.enums.RagJobStep;
import com.init.worker.domain.enums.RagStepTimingStatus;
import jakarta.persistence.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Job 파이프라인 단계(PCC·EMBED·UPSERT 등) 한 번의 실행 시도에 대한 소요 시간 기록.
 * <p>단계 시작 시 {@code started_at} 으로 한 행을 INSERT 하고, 종료 시 {@code ended_at}·{@code duration_ms}
 * 를 채운다. 재시도가 발생하면 시도마다 별도 행이 쌓인다({@code attempt}).
 */
@Entity
@Table(name = "rag_job_step_timings")
public class RagJobStepTiming {

    @Id
    @Column(name = "id")
    private UUID id;

    @Column(name = "job_id", nullable = false)
    private UUID jobId;

    @Column(name = "document_id", nullable = false)
    private UUID documentId;

    @Enumerated(EnumType.STRING)
    @Column(name = "step", length = 30, nullable = false)
    private RagJobStep step;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", length = 20, nullable = false)
    private RagStepTimingStatus status;

    @Column(name = "attempt", nullable = false)
    private int attempt;

    @Column(name = "started_at", nullable = false)
    private OffsetDateTime startedAt;

    @Column(name = "ended_at")
    private OffsetDateTime endedAt;

    @Column(name = "duration_ms")
    private Long durationMs;

    @Column(name = "error_code", length = 50)
    private String errorCode;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false, nullable = false)
    private OffsetDateTime createdAt;

    protected RagJobStepTiming() {}

    public RagJobStepTiming(UUID id, UUID jobId, UUID documentId, RagJobStep step, int attempt) {
        this.id = id;
        this.jobId = jobId;
        this.documentId = documentId;
        this.step = step;
        this.attempt = attempt;
        this.status = RagStepTimingStatus.RUNNING;
        this.startedAt = OffsetDateTime.now();
    }

    /** 이미 종료된 행은 다시 닫지 않는다(멱등). */
    public void close(RagStepTimingStatus finalStatus, String errorCode) {
        if (this.endedAt != null) {
            return;
        }
        this.endedAt = OffsetDateTime.now();
        this.durationMs = Math.max(0, Duration.between(this.startedAt, this.endedAt).toMillis());
        this.status = finalStatus;
        this.errorCode = errorCode;
    }

    /**
     * 미리 측정된 소요 시간으로 종료한다(PCC 하위 단계 PARSE/CLEAN/CHUNK 등).
     * {@code startedAt} 을 {@code endedAt - durationMs} 로 맞춰 표시 정렬이 자연스럽게 한다.
     */
    public void closeWithDuration(RagStepTimingStatus finalStatus, long durationMs, String errorCode) {
        if (this.endedAt != null) {
            return;
        }
        long safeMs = Math.max(0, durationMs);
        this.endedAt = OffsetDateTime.now();
        this.startedAt = this.endedAt.minusNanos(safeMs * 1_000_000L);
        this.durationMs = safeMs;
        this.status = finalStatus;
        this.errorCode = errorCode;
    }

    /** 하위 단계 시간창을 명시적으로 덮어쓴다(PARSE→CLEAN→CHUNK 순서 보정용). */
    public void applyWindow(OffsetDateTime startedAt, OffsetDateTime endedAt, long durationMs) {
        this.startedAt = startedAt;
        this.endedAt = endedAt;
        this.durationMs = Math.max(0, durationMs);
        this.status = RagStepTimingStatus.SUCCEEDED;
    }

    public boolean isOpen() {
        return this.endedAt == null;
    }

    public UUID getId() { return id; }
    public UUID getJobId() { return jobId; }
    public UUID getDocumentId() { return documentId; }
    public RagJobStep getStep() { return step; }
    public RagStepTimingStatus getStatus() { return status; }
    public int getAttempt() { return attempt; }
    public OffsetDateTime getStartedAt() { return startedAt; }
    public OffsetDateTime getEndedAt() { return endedAt; }
    public Long getDurationMs() { return durationMs; }
    public String getErrorCode() { return errorCode; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
}
