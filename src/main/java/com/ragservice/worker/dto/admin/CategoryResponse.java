package com.ragservice.worker.dto.admin;

import com.ragservice.worker.domain.RagCategory;

import java.time.OffsetDateTime;

public record CategoryResponse(
        String categoryId,
        String name,
        String description,
        boolean active,
        String userNo,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt
) {
    public static CategoryResponse of(RagCategory c) {
        return new CategoryResponse(
                c.getId().toString(),
                c.getName(),
                c.getDescription(),
                c.isActive(),
                c.getUserNo().toString(),
                c.getCreatedAt(),
                c.getUpdatedAt()
        );
    }
}
