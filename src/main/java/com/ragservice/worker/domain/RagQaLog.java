package com.ragservice.worker.domain;

import jakarta.persistence.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * QA 요청 로그 엔티티 (rag_qa_log) — 레거시. 신규 citation API는 {@link RagQaCitation} 직접 저장.
 */
@Entity
@Table(name = "rag_qa_log")
public class RagQaLog {

    @Id
    @JdbcTypeCode(SqlTypes.UUID)
    @Column(name = "id")
    private UUID id;

    @Column(name = "message_id", columnDefinition = "uuid", nullable = false)
    private UUID messageId;

    @Column(name = "top_k", nullable = false)
    private int topK;

    @Column(name = "ai_service_name", length = 128)
    private String aiServiceName;

    @Column(name = "model", length = 128)
    private String model;

    @Column(name = "answer", columnDefinition = "text")
    private String answer;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false, nullable = false)
    private OffsetDateTime createdAt;

    protected RagQaLog() {}

    public static RagQaLog create(
            UUID messageId,
            int topK,
            String aiServiceName,
            String model,
            String answer) {
        RagQaLog log = new RagQaLog();
        log.id = UUID.randomUUID();
        log.messageId = messageId;
        log.topK = topK;
        log.aiServiceName = aiServiceName;
        log.model = model;
        log.answer = answer;
        return log;
    }

    public UUID getId() { return id; }
    /** @deprecated getId() 사용 권장 */
    public UUID getQaId() { return id; }
    public UUID getMessageId() { return messageId; }
    public int getTopK() { return topK; }
    public String getAiServiceName() { return aiServiceName; }
    public String getModel() { return model; }
    public String getAnswer() { return answer; }
}
