package com.ragservice.worker.domain.enums;

/**
 * 임베딩 파트(rag_embedding_parts)의 상태.
 * - EMBED 결과를 파트 단위로 관리할 때 사용한다.
 */
public enum RagEmbeddingPartStatus {
    READY,          // 임베딩 생성 완료(업서트 전)
    UPSERTED,       // 벡터 DB(Qdrant 등)에 업서트 완료
    FAILED          // 임베딩/업서트 과정에서 실패
}