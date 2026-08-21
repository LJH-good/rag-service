package com.ragservice.worker.dto;

import jakarta.validation.constraints.NotNull;

import java.util.List;
import java.util.UUID;

/** AI Gateway → RAG: citation DB 저장 요청. */
public record SaveQaCitationsRequest(
        @NotNull(message = "messageId is required")
        UUID messageId,

        List<CitationDto> citations
) {}
