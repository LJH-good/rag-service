package com.ragservice.worker.repo;

import com.ragservice.worker.domain.RagEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface RagEntityRepository extends JpaRepository<RagEntity, UUID> {

    List<RagEntity> findByDocumentId(UUID documentId);

    long countByDocumentIdAndIsDeletedFalse(UUID documentId);

    Optional<RagEntity> findByDocumentIdAndTypeAndName(UUID documentId, String type, String name);

    @Modifying
    @Transactional
    @Query("delete from RagEntity e where e.documentId = :documentId")
    int deleteByDocumentId(@Param("documentId") UUID documentId);
}
