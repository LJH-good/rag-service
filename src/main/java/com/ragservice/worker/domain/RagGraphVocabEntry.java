package com.ragservice.worker.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import org.hibernate.annotations.CreationTimestamp;

import java.time.OffsetDateTime;

/**
 * Graph RAG TYPE/RELATION 어휘 항목.
 * kind='TYPE' 또는 kind='RELATION', name 으로 닫힌 세트를 관리한다.
 * builtin=true 는 초기 seed 대상(재설치·빈 테이블 기동 시).
 * 삭제는 soft-delete({@code is_deleted}/{@code deleted_at}).
 */
@Entity
@Table(
        name = "rag_graph_vocab_entry",
        uniqueConstraints = @UniqueConstraint(
                name = "uq_rag_graph_vocab_kind_name",
                columnNames = {"kind", "name"}
        )
)
public class RagGraphVocabEntry {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "kind", nullable = false, length = 10)
    private String kind;

    @Column(name = "name", nullable = false, length = 100)
    private String name;

    @Column(name = "is_builtin", nullable = false)
    private boolean builtin = true;

    @Column(name = "is_active", nullable = false)
    private boolean active = true;

    @Column(name = "is_deleted", nullable = false)
    private boolean deleted = false;

    @Column(name = "deleted_at")
    private OffsetDateTime deletedAt;

    @Column(name = "sort_order", nullable = false)
    private int sortOrder = 0;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false, nullable = false)
    private OffsetDateTime createdAt;

    protected RagGraphVocabEntry() {}

    public RagGraphVocabEntry(String kind, String name, boolean builtin, int sortOrder) {
        this.kind = kind;
        this.name = name;
        this.builtin = builtin;
        this.active = true;
        this.deleted = false;
        this.sortOrder = sortOrder;
    }

    public Long getId() { return id; }
    public String getKind() { return kind; }
    public String getName() { return name; }
    public boolean isBuiltin() { return builtin; }
    public boolean isActive() { return active; }
    public boolean isDeleted() { return deleted; }
    public OffsetDateTime getDeletedAt() { return deletedAt; }
    public int getSortOrder() { return sortOrder; }
    public OffsetDateTime getCreatedAt() { return createdAt; }

    public void setActive(boolean active) {
        this.active = active;
    }

    public void softDelete(OffsetDateTime at) {
        this.deleted = true;
        this.deletedAt = at;
        this.active = false;
    }

    /** soft-delete 된 항목을 다시 추가할 때 복구 */
    public void restore() {
        this.deleted = false;
        this.deletedAt = null;
        this.active = true;
    }
}
