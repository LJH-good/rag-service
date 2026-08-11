package com.init.worker.dto;

public record EmbeddingResult(
        String itemId,
        float[] embedding
) {
}
