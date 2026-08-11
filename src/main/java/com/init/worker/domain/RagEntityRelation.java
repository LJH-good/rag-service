package com.init.worker.domain;

import jakarta.persistence.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * 엔티티 간 관계(연결 고리). Pass2(EXTRACT_RELATION)가 채운다.
 * - relation      : 정규 RELATION 이름(탐색용, {@code rag_graph_vocab_entry} 닫힌 세트)
 * - relationLabel : LLM 원문 라벨(근거용)
 * - confidence    : 0.0 ~ 1.0
 * 방향성은 한 방향으로만 저장하고 탐색은 양방향 쿼리로 처리한다(설계 7절).
 */
@Entity
@Table(
        name = "rag_entity_relation",
        indexes = {
                @Index(name = "idx_rag_relation_src", columnList = "src_entity_id"),
                @Index(name = "idx_rag_relation_dst", columnList = "dst_entity_id"),
                @Index(name = "idx_rag_relation_document", columnList = "document_id")
        }
)
public class RagEntityRelation {

    @Id
    @Column(name = "relation_id")
    private UUID relationId;

    @Column(name = "document_id", nullable = false)
    private UUID documentId;

    @Column(name = "src_entity_id", nullable = false)
    private UUID srcEntityId;

    @Column(name = "dst_entity_id", nullable = false)
    private UUID dstEntityId;

    @Column(name = "relation", nullable = false)
    private String relation;

    @Column(name = "relation_label")
    private String relationLabel;

    @Column(name = "confidence")
    private Double confidence;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false, nullable = false)
    private OffsetDateTime createdAt;

    @Column(name = "is_deleted", nullable = false)
    private boolean isDeleted = false;

    @Column(name = "deleted_at")
    private OffsetDateTime deletedAt;

    protected RagEntityRelation() {}

    public RagEntityRelation(UUID relationId, UUID documentId,
                             UUID srcEntityId, UUID dstEntityId,
                             String relation, String relationLabel, Double confidence) {
        this.relationId = relationId;
        this.documentId = documentId;
        this.srcEntityId = srcEntityId;
        this.dstEntityId = dstEntityId;
        this.relation = relation;
        this.relationLabel = relationLabel;
        this.confidence = confidence;
    }

    public UUID getRelationId() { return relationId; }
    public UUID getDocumentId() { return documentId; }
    public UUID getSrcEntityId() { return srcEntityId; }
    public UUID getDstEntityId() { return dstEntityId; }
    public String getRelation() { return relation; }
    public String getRelationLabel() { return relationLabel; }
    public Double getConfidence() { return confidence; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public boolean isDeleted() { return isDeleted; }
    public OffsetDateTime getDeletedAt() { return deletedAt; }

    public void softDelete(OffsetDateTime at) {
        this.isDeleted = true;
        this.deletedAt = at;
    }
}
