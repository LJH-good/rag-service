package com.init.worker.repo;

import com.init.worker.domain.RagChunk;
import org.springframework.data.domain.Pageable;

import java.util.List;
import java.util.UUID;

public interface RagChunkRepositoryCustom {

    int deleteByDoc(UUID documentId);

    List<RagChunk> findPageByDoc(UUID documentId, int fromIndex, Pageable pageable);

    long countByDoc(UUID documentId);
}
