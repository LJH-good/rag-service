package com.init.worker.dto;

public record EmbeddingInput(
        String itemId,
        String content
) {
}