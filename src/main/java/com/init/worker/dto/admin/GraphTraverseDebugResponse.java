package com.init.worker.dto.admin;

import java.util.List;

/**
 * 어드민 임시 테스트용 — Traverse 중간 결과(seed/이웃/청크 가중).
 */
public record GraphTraverseDebugResponse(
        boolean graphEnabled,
        String query,
        String normalizedQuery,
        String documentId,
        List<EntityHit> seeds,
        List<EntityHit> neighbors,
        List<HopRelation> hopRelations,
        List<ChunkHit> chunkHits
) {
    public record EntityHit(
            String entityId,
            String name,
            String type
    ) {}

    public record HopRelation(
            String relationId,
            String srcEntityId,
            String srcName,
            String dstEntityId,
            String dstName,
            String relation,
            String relationLabel,
            Double confidence
    ) {}

    public record ChunkHit(
            String chunkId,
            double weight,
            List<String> entityIds
    ) {}
}
