package com.ragservice.worker.dto;

import java.util.List;

/** RAG 벡터 검색 응답. */
public record RetrieveResponse(
        List<CitationDto> citations,
        boolean graphApplied,
        int graphChunkCount,
        int graphOnlyPromotedCount
) {
    public RetrieveResponse(List<CitationDto> citations) {
        this(citations, false, 0, 0);
    }
}
