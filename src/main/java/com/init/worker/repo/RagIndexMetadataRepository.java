package com.init.worker.repo;

import com.init.worker.domain.RagIndexMetadata;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

/**
 * rag_index_metadata 테이블 접근 Repository.
 * - 문서 1건당 인덱싱 결과 메타 1건을 조회하는 용도(문서별 조회).
 */
public interface RagIndexMetadataRepository extends JpaRepository<RagIndexMetadata, UUID> {

    /** documentId로 인덱싱 메타 조회(문서당 1건) */
    Optional<RagIndexMetadata> findByDocumentId(UUID documentId);
}
