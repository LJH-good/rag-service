package com.init.worker.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.util.UUID;

/** 클라이언트 QA 요청 (E2E). */
public record AskRequest(
        @NotNull(message = "sessionId is required")
        UUID sessionId,

        @NotNull(message = "messageId is required")
        UUID messageId,

        @NotBlank(message = "content is required")
        String content,

        UUID categoryId,

        String documentId,

        String modelCode,

        /**
         * 요청 단위 그래프 사용 여부.
         * null → {@code rag.graph.enabled} 설정 따름.
         * false → 강제 벡터-only.
         * true → 설정이 켜져 있을 때만 그래프 보강.
         */
        Boolean graphEnabled
) {
    /** 하위 호환: graphEnabled 미지정. */
    public AskRequest(
            UUID sessionId,
            UUID messageId,
            String content,
            UUID categoryId,
            String documentId,
            String modelCode) {
        this(sessionId, messageId, content, categoryId, documentId, modelCode, null);
    }
}
