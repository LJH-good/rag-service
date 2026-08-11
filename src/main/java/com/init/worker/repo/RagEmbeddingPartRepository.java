package com.init.worker.repo;

import com.init.worker.domain.RagEmbeddingPart;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.UUID;

public interface RagEmbeddingPartRepository
        extends JpaRepository<RagEmbeddingPart, UUID>,
        RagEmbeddingPartRepositoryCustom {
}
