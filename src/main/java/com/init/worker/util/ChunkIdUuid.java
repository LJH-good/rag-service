package com.init.worker.util;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

/** Qdrant payload chunk_id(임의 문자열) → rag_qa_citation.chunk_id(UUID) 변환. */
public final class ChunkIdUuid {

    private ChunkIdUuid() {}

    public static UUID parseOrDerive(String chunkId) {
        if (chunkId == null || chunkId.isBlank()) {
            return null;
        }
        String s = chunkId.trim();
        try {
            return UUID.fromString(s);
        } catch (IllegalArgumentException ignored) {
            return UUID.nameUUIDFromBytes(s.getBytes(StandardCharsets.UTF_8));
        }
    }
}
