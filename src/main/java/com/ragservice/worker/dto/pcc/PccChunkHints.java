package com.ragservice.worker.dto.pcc;

import com.ragservice.worker.domain.enums.ChunkMode;

/**
 * LangChain PCC에 전달하는 청킹 힌트.
 *
 * mode=FIXED  : maxChars 기준 고정 길이 슬라이스 (기존 동작).
 * mode=SEMANTIC: 문장 임베딩 유사도 breakpoint로 경계를 결정하고,
 *               maxChars는 임베딩 모델 토큰 한도를 넘지 않기 위한 하드 캡으로만 작동한다.
 *               초과 시 PCC는 재귀적 재분할을 먼저 시도하고, 그래도 실패하면 overlap 슬라이스로 폴백한다.
 */
public record PccChunkHints(int maxChars, int overlapChars, int minChars, ChunkMode mode) {}
