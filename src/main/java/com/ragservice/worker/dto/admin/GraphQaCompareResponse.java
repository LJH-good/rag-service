package com.ragservice.worker.dto.admin;

import com.ragservice.worker.dto.CitationDto;

import java.util.List;
import java.util.UUID;

/** 어드민 임시 — 벡터-only vs Graph RAG QA 비교 응답. */
public record GraphQaCompareResponse(
        String query,
        boolean configGraphEnabled,
        String aiServiceName,
        ModeResult vectorOnly,
        ModeResult graphAugmented,
        CitationDiff citationDiff
) {
    public record ModeResult(
            boolean graphRequested,
            boolean graphApplied,
            int graphChunkCount,
            int graphOnlyPromotedCount,
            long latencyMs,
            UUID sessionId,
            UUID messageId,
            String answer,
            String modelName,
            String provider,
            List<CitationDto> citations
    ) {}

    public record CitationDiff(
            List<String> onlyInVector,
            List<String> onlyInGraph,
            List<String> shared
    ) {}
}
