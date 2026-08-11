package com.init.worker.repo;

import com.init.worker.domain.RagEntityRelation;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

public interface RagEntityRelationRepository extends JpaRepository<RagEntityRelation, UUID> {

    List<RagEntityRelation> findByDocumentId(UUID documentId);

    long countByDocumentIdAndIsDeletedFalse(UUID documentId);

    /** 그래프 탐색: 특정 엔티티가 src 또는 dst 로 참여한 관계(양방향). */
    List<RagEntityRelation> findBySrcEntityIdInOrDstEntityIdIn(List<UUID> srcEntityIds, List<UUID> dstEntityIds);

    @Modifying
    @Transactional
    @Query("delete from RagEntityRelation r where r.documentId = :documentId")
    int deleteByDocumentId(@Param("documentId") UUID documentId);
}
