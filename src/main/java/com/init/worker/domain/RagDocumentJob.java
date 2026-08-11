package com.init.worker.domain;

import com.init.worker.domain.enums.RagJobStatus;
import com.init.worker.domain.enums.RagJobStep;
import com.init.worker.domain.enums.RagTriggerType;
import jakarta.persistence.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.OffsetDateTime;
import java.util.UUID;

@Entity
@Table(name = "rag_document_jobs")
public class RagDocumentJob {

    @Id
    @Column(name = "id")
    private UUID id;

    @Column(name = "document_id", nullable = false)
    private UUID documentId;

    @Enumerated(EnumType.STRING)
    @Column(name = "trigger_type", length = 30, nullable = false)
    private RagTriggerType triggerType;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", length = 30, nullable = false)
    private RagJobStatus status;

    @Enumerated(EnumType.STRING)
    @Column(name = "current_step", length = 30)
    private RagJobStep currentStep;

    @Column(name = "error_code", length = 50)
    private String errorCode;

    @Column(name = "error_message")
    private String errorMessage;

    @Column(name = "transaction_id")
    private UUID transactionId;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false, nullable = false)
    private OffsetDateTime createdAt;

    @Column(name = "started_at")
    private OffsetDateTime startedAt;

    @Column(name = "ended_at")
    private OffsetDateTime endedAt;

    protected RagDocumentJob() {}

    public RagDocumentJob(UUID id, UUID documentId, RagTriggerType triggerType, UUID transactionId) {
        this.id = id;
        this.documentId = documentId;
        this.triggerType = triggerType;
        this.status = RagJobStatus.PENDING;
        this.transactionId = transactionId;
    }

    public UUID getId() { return id; }
    public UUID getJobId() { return id; }
    public UUID getDocumentId() { return documentId; }
    public RagTriggerType getTriggerType() { return triggerType; }
    public RagJobStep getCurrentStep() { return currentStep; }
    public RagJobStatus getStatus() { return status; }
    public UUID getTransactionId() { return transactionId; }
    public String getErrorCode() { return errorCode; }
    public String getErrorMessage() { return errorMessage; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public OffsetDateTime getStartedAt() { return startedAt; }
    public OffsetDateTime getEndedAt() { return endedAt; }

    public void claim() {
        this.status = RagJobStatus.RUNNING;
        if (this.startedAt == null) {
            this.startedAt = OffsetDateTime.now();
        }
    }

    public void moveTo(RagJobStep nextStep) {
        // 다음 단계 워커가 pick(PENDING + step) 할 수 있도록 대기열로 되돌린다.
        this.status = RagJobStatus.PENDING;
        this.currentStep = nextStep.toDbStep();
        this.errorCode = null;
        this.errorMessage = null;
        this.endedAt = null;
    }

    public void fail(String code, String message) {
        this.status = RagJobStatus.FAILED;
        this.currentStep = null;
        this.errorCode = code;
        this.errorMessage = message;
        this.endedAt = OffsetDateTime.now();
    }

    /**
     * 일시 장애(외부 API 타임아웃/일시적 네트워크 장애 등)는 FAILED로 종료하지 않고
     * 동일 단계를 다시 처리할 수 있도록 대기열(PENDING)로 되돌린다.
     */
    public void requeueForRetry() {
        this.status = RagJobStatus.PENDING;
        this.errorCode = null;
        this.errorMessage = null;
        this.endedAt = null;
    }

    public void succeed() {
        this.status = RagJobStatus.SUCCEEDED;
        this.currentStep = null;
        this.errorCode = null;
        this.errorMessage = null;
        this.endedAt = OffsetDateTime.now();
    }

    /**
     * 청크 storage_key 가 원본 경로와 어긋난 경우 PCC 를 다시 타도록 초기화한다.
     */
    public void resetForPccRetry() {
        this.status = RagJobStatus.PENDING;
        this.currentStep = null;
        this.errorCode = null;
        this.errorMessage = null;
        this.startedAt = null;
        this.endedAt = null;
    }
}
