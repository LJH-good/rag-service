package com.ragservice.worker.dto;

public record EmbeddingInput(
        String itemId,
        String content
) {
}