package com.ragservice.worker.dto.admin;

import java.util.List;

/**
 * Graph RAG TYPE/RELATION 어휘 세트 (DB {@code rag_graph_vocab_entry} 기반).
 * soft-delete 된 항목은 포함하지 않는다.
 */
public record GraphVocabularyResponse(
        List<GraphVocabularyItem> entityTypes,
        String entityTypeDefault,
        List<GraphVocabularyItem> relationTypes,
        String relationTypeDefault,
        String source
) {}
