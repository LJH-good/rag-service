package com.init.worker.dto.pcc;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * LangChain parse-clean 응답 — 파싱·정제까지만 수행한 정리 전 문서 전체 텍스트(Graph RAG Pass1 입력).
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record PccParseCleanResponse(
        String text,
        PccIngestResponse.PccStageTimings timings
) {}
