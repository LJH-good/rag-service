package com.init.worker.dto;

import jakarta.validation.constraints.NotBlank;

import java.util.UUID;

/** RAG 벡터 검색 요청 (citations only). */
public record RetrieveRequest(
        @NotBlank(message = "searchQuery is required")
        String searchQuery,

        UUID categoryId,

        String documentId,

        /**
         * 요청 단위 그래프 사용 여부.
         * null → {@code rag.graph.enabled} 설정 따름.
         * false → 강제 벡터-only.
         * true → 설정이 켜져 있을 때만 그래프 보강.
         */
        Boolean graphEnabled
) {
    public RetrieveRequest(String searchQuery, UUID categoryId, String documentId) {
        this(searchQuery, categoryId, documentId, null);
    }
}
