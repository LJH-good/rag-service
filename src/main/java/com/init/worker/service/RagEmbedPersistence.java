package com.init.worker.service;

import com.init.worker.repo.RagChunkRepository;
import com.init.worker.repo.RagEmbeddingPartRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * EMBED 단계에서 필요한 bulk delete 를 짧은 트랜잭션으로 묶는다.
 * (QueryDSL bulk delete 는 활성 트랜잭션이 필요하다.)
 */
@Service
@RequiredArgsConstructor
public class RagEmbedPersistence {

    private final RagEmbeddingPartRepository partRepo;
    private final RagChunkRepository chunkRepo;

    @Transactional
    public void clearEmbeddingPartsForDocument(UUID documentId) {
        partRepo.deleteByDocumentId(documentId);
    }

    @Transactional
    public void resetForPccRetry(UUID documentId) {
        partRepo.deleteByDocumentId(documentId);
        chunkRepo.deleteByDoc(documentId);
        partRepo.deletePartsWithMissingChunk();
    }
}
