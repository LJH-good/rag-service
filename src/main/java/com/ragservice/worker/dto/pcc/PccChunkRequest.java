package com.ragservice.worker.dto.pcc;

/**
 * LangChain chunk 요청 — Pass1 정리본(또는 폴백 텍스트)을 청킹만 수행한다.
 */
public record PccChunkRequest(String text, PccChunkHints chunk) {}
