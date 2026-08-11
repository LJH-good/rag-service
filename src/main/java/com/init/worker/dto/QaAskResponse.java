package com.init.worker.dto;

import java.util.List;
import java.util.UUID;

/** 클라이언트 QA 응답 (E2E). */
public record QaAskResponse(
        UUID messageId,
        UUID sessionId,
        String answer,
        String modelName,
        String provider,
        List<CitationDto> citations
) {}
