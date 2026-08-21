package com.ragservice.worker.langchain;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;
import java.util.UUID;

/**
 * LangChain 서비스에서 내려오는 QA 응답 전용 DTO.
 */
public record LangchainAskResponse(
        UUID messageId,
        String answer,
        List<LangchainCitation> citations,
        @JsonProperty("modelName") @JsonAlias("model_name") String modelName,
        String provider
) {

    public record LangchainCitation(
            @JsonProperty("chunkId") @JsonAlias("chunk_id") String chunkId,
            @JsonProperty("documentId") @JsonAlias("document_id") String documentId,
            Double score,
            Integer page,
            Integer slide,
            String sheet,
            @JsonProperty("sourceUri") @JsonAlias("source_uri") String sourceUri,
            String text
    ) {
    }
}
