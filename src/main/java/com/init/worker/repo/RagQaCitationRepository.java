package com.init.worker.repo;

import com.init.worker.domain.RagQaCitation;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface RagQaCitationRepository extends JpaRepository<RagQaCitation, UUID> {
    List<RagQaCitation> findByMessageIdOrderByScoreDesc(UUID messageId);

    void deleteByMessageId(UUID messageId);
}
