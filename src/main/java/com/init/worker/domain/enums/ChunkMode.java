package com.init.worker.domain.enums;

/**
 * PCC 청킹 알고리즘 선택.
 *
 * FIXED    — 글자 수(max-chars) 기준 고정 길이 청킹 (기존 방식).
 * SEMANTIC — 문장 임베딩 유사도 breakpoint 기반 의미 청킹.
 *            max-chars는 기술적 안전장치(임베딩 모델 토큰 한도)로만 작동한다.
 */
public enum ChunkMode {
    FIXED,
    SEMANTIC
}
