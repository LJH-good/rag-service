package com.init.worker.dto.admin;

import java.util.List;

/** 카테고리 내 문서별 Graph RAG 적재 커버리지 (2-1 진단용). */
public record GraphCategoryCoverageResponse(
        String categoryId,
        int documentCount,
        int graphReadyCount,
        int graphEmptyCount,
        List<DocumentCoverage> documents
) {
    public record DocumentCoverage(
            String documentId,
            String originalFileName,
            long entityCount,
            long relationCount,
            long bridgeCount,
            boolean graphReady
    ) {}
}
