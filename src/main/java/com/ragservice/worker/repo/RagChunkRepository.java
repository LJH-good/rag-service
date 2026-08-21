package com.ragservice.worker.repo;

import com.ragservice.worker.domain.RagChunk;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface RagChunkRepository extends JpaRepository<RagChunk, UUID>, RagChunkRepositoryCustom {
}
