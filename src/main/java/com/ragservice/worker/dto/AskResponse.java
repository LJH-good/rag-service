package com.ragservice.worker.dto;

import java.util.List;
import java.util.UUID;

/** citation 조회 응답 DTO (messageId 기준). */
public record AskResponse(
        UUID messageId,
        List<CitationDto> citations,
        String modelName,
        String provider
) {}
