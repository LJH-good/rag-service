package com.init.worker.dto.admin;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * 어드민 임시 테스트용 — 문서 단위 그래프 스냅샷.
 */
public record GraphDocumentViewResponse(
        String documentId,
        boolean graphEnabled,
        int entityCount,
        int relationCount,
        int bridgeCount,
        List<EntityItem> entities,
        List<RelationItem> relations,
        List<BridgeItem> bridges
) {
    public record EntityItem(
            String entityId,
            String name,
            String type,
            OffsetDateTime createdAt
    ) {}

    public record RelationItem(
            String relationId,
            String srcEntityId,
            String srcName,
            String dstEntityId,
            String dstName,
            String relation,
            String relationLabel,
            Double confidence,
            OffsetDateTime createdAt
    ) {}

    public record BridgeItem(
            String id,
            String entityId,
            String entityName,
            String chunkId,
            OffsetDateTime createdAt
    ) {}
}
