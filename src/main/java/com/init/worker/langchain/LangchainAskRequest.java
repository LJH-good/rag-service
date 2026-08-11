package com.init.worker.langchain;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.UUID;

/**
 * langchain-service QA API 계약에 맞는 요청 바디.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record LangchainAskRequest(
        UUID messageId,
        String content,
        UUID categoryId,
        String documentId,
        String model
) {
    public static LangchainAskRequest from(com.init.worker.dto.AskRequest req) {
        return new LangchainAskRequest(
                req.messageId(),
                req.content(),
                req.categoryId(),
                req.documentId(),
                req.modelCode()
        );
    }
}
