package com.ragservice.worker.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public record EmbeddingBatchResponse(
        List<EmbeddingResult> results
) {
}
