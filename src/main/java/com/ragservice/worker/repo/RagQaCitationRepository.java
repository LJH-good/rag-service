package com.ragservice.worker.repo;

import com.ragservice.worker.domain.RagQaCitation;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface RagQaCitationRepository extends JpaRepository<RagQaCitation, UUID> {
    List<RagQaCitation> findByMessageIdOrderByScoreDesc(UUID messageId);

    void deleteByMessageId(UUID messageId);
}
