package com.ragservice.worker.dto;

import java.math.BigDecimal;

/**
 * 답변 근거(citation) 1건 DTO.
 * - chunkId: 청크 식별자 (청크 삭제 후에도 snippet 으로 내용 보존)
 * - documentId: 원본 문서 식별자
 * - score: 벡터 유사도 점수 (0.0000 ~ 1.0000)
 * - page: 페이지 번호 (PDF 등, 없으면 null)
 * - slide: 슬라이드 번호 (PPT 등, 없으면 null)
 * - sheet: 시트명 (Excel 등, 없으면 null)
 * - sourceUri: 원본 문서 URI (없으면 null)
 * - snippet: 미리보기 텍스트
 */
public record CitationDto(
        String chunkId,
        String documentId,
        BigDecimal score,
        Integer page,
        Integer slide,
        String sheet,
        String sourceUri,
        String snippet
) {}
