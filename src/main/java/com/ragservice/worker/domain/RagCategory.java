package com.ragservice.worker.domain;

import jakarta.persistence.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.OffsetDateTime;
import java.util.UUID;

@Entity
@Table(name = "rag_categories", schema = "rag")
public class RagCategory {
    @Id
    @Column(name = "id", columnDefinition = "uuid")
    private UUID id;

    @Column(name = "name", nullable = false)
    private String name;

    @Column(name = "description", length = 500)
    private String description;

    @Column(name = "user_no", columnDefinition = "uuid", nullable = false)
    private UUID userNo;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false, nullable = false)
    private OffsetDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    @Column(name = "is_active", nullable = false)
    private boolean active = true;

    @Column(name = "is_deleted", nullable = false)
    private boolean isDeleted = false;

    @Column(name = "deleted_at")
    private OffsetDateTime deletedAt;

    protected RagCategory() {}
    public RagCategory(UUID id, String name, String description, UUID userNo) {
        this.id = id;
        this.name = name;
        this.description = description;
        this.userNo = userNo;
    }

    public UUID getId() { return id; }

    public String getName() { return name; }

    public String getDescription() { return description; }

    public UUID getUserNo() { return userNo; }

    public OffsetDateTime getCreatedAt() { return createdAt; }

    public OffsetDateTime getUpdatedAt() { return updatedAt; }

    public boolean isActive() { return active; }

    public boolean isDeleted() { return isDeleted; }

    public OffsetDateTime getDeletedAt() { return deletedAt; }

    public void update(String name, String description, Boolean active) {
        if (name != null) {
            this.name = name;
        }
        if (description != null) {
            this.description = description;
        }
        if (active != null) {
            this.active = active;
        }
    }

    public void softDelete(OffsetDateTime at) {
        this.isDeleted = true;
        this.deletedAt = at;
    }
}