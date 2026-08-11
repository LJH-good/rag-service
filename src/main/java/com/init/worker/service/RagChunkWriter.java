package com.init.worker.service;

import com.init.worker.domain.RagChunk;
import com.init.worker.domain.RagDocument;
import com.init.worker.domain.RagDocumentFile;
import com.init.worker.dto.pcc.PccIngestResponse.PccChunkPayload;
import com.init.worker.repo.RagChunkRepository;
import com.init.worker.repo.RagEmbeddingPartRepository;
import com.init.worker.storage.StorageClient;
import com.init.worker.util.IdGenerator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/**
 * PCC / Pass1(EXTRACT_ENTITY) 공통 — 청크 본문을 MinIO(chunk.txt) + PostgreSQL(rag_chunks)에 저장한다.
 * 두 진입 워커(RagPccWorker, RagGraphEntityWorker)가 동일한 청크 저장 규칙을 공유하기 위한 헬퍼.
 *
 * <p>DB 작업만 짧은 {@code @Transactional}로 묶고, MinIO upload는 트랜잭션 밖에서 수행한다.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class RagChunkWriter {

    private final RagChunkRepository chunkRepo;
    private final RagEmbeddingPartRepository partRepo;
    private final StorageClient storageClient;
    private final RagDocumentLoader documentLoader;

    /** 이전 파이프라인 산출물(임베딩 parts / chunks / 고아 parts) 제거 — 재실행 idempotency. */
    @Transactional
    public void clearPriorArtifacts(UUID documentId, String traceId, String logPrefix) {
        int removedParts = partRepo.deleteByDocumentId(documentId);
        int removedChunks = chunkRepo.deleteByDoc(documentId);
        int removedOrphans = partRepo.deletePartsWithMissingChunk();
        if (removedParts > 0 || removedChunks > 0 || removedOrphans > 0) {
            log.info("[{}][{}] cleared prior pipeline artifacts. parts={}, chunks={}, orphanParts={}",
                    logPrefix, traceId, removedParts, removedChunks, removedOrphans);
        }
    }

    /**
     * 청크 payload 목록을 index 순서로 저장한다(빈 텍스트 제외). 저장된 청크 수 반환.
     * MinIO upload는 TX 밖, {@code rag_chunks} 저장은 repository 기본 {@code @Transactional}로 짧게 수행된다.
     */
    public int store(RagDocument doc, RagDocumentFile file, List<PccChunkPayload> chunks, String traceId) {
        List<PccChunkPayload> ordered = new ArrayList<>(chunks);
        ordered.sort(Comparator.comparingInt(p -> p.index() != null ? p.index() : Integer.MAX_VALUE));

        List<RagChunk> entities = new ArrayList<>();
        int globalChunkIndex = 0;
        for (PccChunkPayload p : ordered) {
            String content = p.text() == null ? "" : p.text().trim();
            if (content.isBlank()) {
                continue;
            }
            String chunkStorageKey = documentLoader.canonicalChunkStorageKey(file, doc.getId(), globalChunkIndex);
            storageClient.upload(
                    chunkStorageKey,
                    content.getBytes(StandardCharsets.UTF_8),
                    "chunk.txt",
                    traceId
            );
            entities.add(new RagChunk(
                    IdGenerator.newId(),
                    doc.getId(),
                    globalChunkIndex,
                    chunkStorageKey,
                    content.length(),
                    p.location()
            ));
            globalChunkIndex++;
        }
        if (entities.isEmpty()) {
            return 0;
        }
        chunkRepo.saveAll(entities);
        return entities.size();
    }
}
