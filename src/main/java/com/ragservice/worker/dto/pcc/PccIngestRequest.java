package com.ragservice.worker.dto.pcc;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * LangChain 통합 PCC 요청. 원본 바이트 대신 presigned GET URL만 전달한다.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record PccIngestRequest(
        String objectUrl,
        String mimeType,
        String originalFileName,
        String documentId,
        String categoryId,
        String jobId,
        PccChunkHints chunk,
        /** SEMANTIC 청킹 시 문장 임베딩에 사용할 UAK. FIXED 모드이면 null로 전달해도 무방하다. */
        String embeddingApiKey
) {}
