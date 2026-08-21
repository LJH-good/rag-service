package com.ragservice.worker.dto;

import java.util.List;

public record EmbeddingBatchRequest(
        List<EmbeddingInput> inputs
) {
}
