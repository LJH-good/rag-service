package com.ragservice.worker.repo;

import com.ragservice.worker.domain.RagEmbeddingPart;
import com.ragservice.worker.domain.enums.RagEmbeddingPartStatus;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface RagEmbeddingPartRepositoryCustom {

    /**
     * 특정 문서의 특정 상태 파트 1건을 조회한다 (rag_chunks 조인).
     */
    Optional<RagEmbeddingPart> findFirstByDocAndStatus(UUID documentId, RagEmbeddingPartStatus status);

    /**
     * 특정 문서의 특정 상태 파트 수 (rag_chunks 조인).
     */
    long countByDocumentIdAndStatus(UUID documentId, RagEmbeddingPartStatus status);

    /**
     * 특정 문서의 모든 임베딩 파트 삭제 (rag_chunks 조인).
     */
    int deleteByDocumentId(UUID documentId);

    /**
     * rag_chunks 가 삭제된 뒤 남은 embedding part(row) 를 제거한다.
     */
    int deletePartsWithMissingChunk();

    /**
     * 특정 문서·상태의 임베딩 파트 삭제 (rag_chunks 조인).
     */
    int deleteByDocumentIdAndStatus(UUID documentId, RagEmbeddingPartStatus status);

    /**
     * 특정 문서의 모든 임베딩 파트 조회 (rag_chunks 조인 + chunk_index 오름차순 정렬).
     */
    List<RagEmbeddingPart> findPartsByDocumentId(UUID documentId);
}
