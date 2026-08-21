package com.ragservice.worker.repo;

import com.ragservice.worker.domain.RagEmbeddingPart;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.UUID;

public interface RagEmbeddingPartRepository
        extends JpaRepository<RagEmbeddingPart, UUID>,
        RagEmbeddingPartRepositoryCustom {
}
