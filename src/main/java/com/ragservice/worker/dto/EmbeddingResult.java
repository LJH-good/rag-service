package com.ragservice.worker.dto;

public record EmbeddingResult(
        String itemId,
        float[] embedding
) {
}
