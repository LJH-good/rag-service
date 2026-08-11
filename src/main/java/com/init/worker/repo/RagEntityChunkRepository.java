package com.init.worker.repo;

import com.init.worker.domain.RagEntityChunk;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

public interface RagEntityChunkRepository extends JpaRepository<RagEntityChunk, UUID> {

    /** 브리지: 엔티티 → 청크 환원(citation 재료). */
    List<RagEntityChunk> findByEntityIdIn(List<UUID> entityIds);

    List<RagEntityChunk> findByChunkId(UUID chunkId);

    @Query("""
            select count(ec) from RagEntityChunk ec
            where ec.isDeleted = false
              and ec.entityId in (
                select e.entityId from RagEntity e
                where e.documentId = :documentId and e.isDeleted = false
              )
            """)
    long countActiveByDocumentId(@Param("documentId") UUID documentId);

    /**
     * 문서 소속 엔티티의 브리지만 제거(엔티티는 보존). Pass2 재실행 시 Pass1 canonical 엔티티를
     * 유지한 채 브리지·관계만 다시 만들기 위한 정리용.
     */
    @Modifying
    @Transactional
    @Query("delete from RagEntityChunk ec where ec.entityId in " +
            "(select e.entityId from RagEntity e where e.documentId = :documentId)")
    int deleteByDocumentId(@Param("documentId") UUID documentId);
}
