package com.ragservice.worker.dto;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * AI 응답 스트리밍 청크 DTO
 *
 * - 스트리밍 중간 청크 → content + isLast=false
 * - 첫 청크 → content + 메타데이터(model, provider 등)
 * - 마지막 청크 → isLast=true + usage + cost + finishReason
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)  // null 필드는 JSON에서 제외
public class StreamChunkDTO {

    // --- 기본 데이터 (모든 청크 공통) ---

    /** 스트리밍 텍스트 조각 */
    private String content;

    /** 스트리밍 종료 여부 */
    private Boolean isLast;

    // --- 첫 청크에서만 사용 ---

    /** 요청 유저 no */
    private String userNo;

    /** 요청 추적 ID */
    private String requestId;

    /** 모델 제공자 (openai / claude / gemini 등) */
    @JsonAlias("serviceName")
    private String provider;

    /** 실제 사용 모델명 */
    @JsonAlias("modelCode")
    private String model;

    /** AI 응답 ID */
    private String responseId;

    // --- 마지막 청크에서만 사용 (Usage) ---

    private Integer inputTokens;
    private Integer outputTokens;
    private Integer totalTokens;
    private Integer cachedTokens;

    /** stop / length / filter 등 종료 사유 */
    private String finishReason;

    // --- 마지막 청크에서만 사용 (Cost & Metrics) ---

    /** 사용 비용 (USD) */
    private Double cost;

    /** 총 처리 시간 (ms) */
    private Long processingTimeMs;

    /** 청크 생성 시각 */
    private LocalDateTime timestamp;

    // --- RAG QA 마지막 청크 (isLast=true) ---

    private java.util.UUID messageId;
    private java.util.UUID sessionId;
    private java.util.List<CitationDto> citations;

}
