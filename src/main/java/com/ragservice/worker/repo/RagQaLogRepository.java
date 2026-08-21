package com.ragservice.worker.repo;

import com.ragservice.worker.domain.RagQaLog;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

/**
 * QA 로그 저장/조회 Repository.
 * - logRepo.save(log) 시 citations도 cascade로 함께 저장된다.
 */
public interface RagQaLogRepository extends JpaRepository<RagQaLog, String> {

    Optional<RagQaLog> findTopByMessageIdOrderByCreatedAtDesc(UUID messageId);
}
