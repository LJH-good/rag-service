package com.ragservice.worker.service;

import com.ragservice.worker.config.RagProperties;
import com.ragservice.worker.domain.RagDocument;
import com.ragservice.worker.domain.RagDocumentFile;
import com.ragservice.worker.domain.RagIndexMetadata;
import com.ragservice.worker.qdrant.QdrantClient;
import com.ragservice.worker.repo.RagDocumentFileRepository;
import com.ragservice.worker.repo.RagDocumentRepository;
import com.ragservice.worker.repo.RagEntityChunkRepository;
import com.ragservice.worker.repo.RagEntityRelationRepository;
import com.ragservice.worker.repo.RagEntityRepository;
import com.ragservice.worker.repo.RagIndexMetadataRepository;
import com.ragservice.worker.storage.StorageClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * soft-delete 직후 VectorDB·Graph RAG 색인 데이터를 제거하고,
 * 보관 기간이 지난 문서의 원본 스토리지도 정리한다.
 * 문서/파일 DB 행은 로그 목적으로 유지한다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RagDocumentPurgeService {

    private static final int BATCH_SIZE = 50;

    private final RagProperties props;
    private final RagDocumentRepository documentRepo;
    private final RagDocumentFileRepository fileRepo;
    private final RagIndexMetadataRepository indexMetaRepo;
    private final RagEntityRepository entityRepo;
    private final RagEntityRelationRepository relationRepo;
    private final RagEntityChunkRepository entityChunkRepo;
    private final StorageClient storageClient;
    private final QdrantClient qdrantClient;

    @Transactional
    public int purgeExpired() {
        int retentionDays = resolveRetentionDays();
        OffsetDateTime cutoff = OffsetDateTime.now().minusDays(retentionDays);
        List<RagDocument> candidates = documentRepo.findCandidatesForPurge(
                cutoff, PageRequest.of(0, BATCH_SIZE));
        if (candidates.isEmpty()) {
            return 0;
        }

        int purged = 0;
        for (RagDocument doc : candidates) {
            try {
                purgeOne(doc);
                purged++;
            } catch (Exception e) {
                log.error("[PURGE] failed. documentId={}, reason={}", doc.getId(), e.getMessage(), e);
            }
        }
        log.info("[PURGE] batch done. purged={}, retentionDays={}, cutoff={}", purged, retentionDays, cutoff);
        return purged;
    }

    private void purgeOne(RagDocument doc) {
        UUID documentId = doc.getId();
        String traceId = "purge-" + documentId;

        RagDocumentFile file = fileRepo.findById(doc.getFileId()).orElse(null);
        if (file != null) {
            String storageKey = file.getStorageKey();
            if (storageKey != null && !storageKey.isBlank()) {
                try {
                    storageClient.delete(storageKey, traceId);
                } catch (Exception e) {
                    log.warn("[PURGE] storage delete failed. documentId={}, key={}, reason={}",
                            documentId, storageKey, e.getMessage());
                }
                file.clearStorageAfterPurge();
                fileRepo.save(file);
            }
        }

        indexMetaRepo.findByDocumentId(documentId).ifPresent(meta -> purgeQdrant(meta, traceId));
        deleteGraphData(documentId);

        doc.markPurged(OffsetDateTime.now());
        documentRepo.save(doc);
        log.info("[PURGE] completed. documentId={}, deletedAt={}", documentId, doc.getDeletedAt());
    }

    /**
     * 문서 soft-delete 직후 검색 색인(VectorDB + Graph RAG)을 즉시 제거한다.
     */
    public void purgeIndexedData(UUID documentId) {
        deleteQdrantVectors(documentId);
        deleteGraphData(documentId);
    }

    public void deleteQdrantVectors(UUID documentId) {
        indexMetaRepo.findByDocumentId(documentId).ifPresent(meta -> purgeQdrant(meta, "delete-" + documentId));
    }

    /**
     * 문서 단위 Graph RAG 산출물(관계 → 브리지 → 엔티티)을 제거한다.
     * FK/서브쿼리 의존 때문에 재인덱싱 워커와 동일한 순서를 유지한다.
     */
    public void deleteGraphData(UUID documentId) {
        int relations = relationRepo.deleteByDocumentId(documentId);
        int bridges = entityChunkRepo.deleteByDocumentId(documentId);
        int entities = entityRepo.deleteByDocumentId(documentId);
        log.info("[PURGE] graph deleted. documentId={}, relations={}, bridges={}, entities={}",
                documentId, relations, bridges, entities);
    }

    private void purgeQdrant(RagIndexMetadata meta, String traceId) {
        if (props.qdrant() == null || props.qdrant().baseUrl() == null || props.qdrant().baseUrl().isBlank()) {
            log.warn("[PURGE] qdrant config missing. skip vector purge. documentId={}", meta.getDocumentId());
            return;
        }
        String collection = meta.getCollection() != null && !meta.getCollection().isBlank()
                ? meta.getCollection()
                : props.qdrant().collection();
        try {
            qdrantClient.deleteByDocument(
                    props.qdrant().baseUrl(),
                    collection,
                    meta.getDocumentId().toString(),
                    traceId);
        } catch (Exception e) {
            log.warn("[PURGE] qdrant delete failed. documentId={}, collection={}, reason={}",
                    meta.getDocumentId(), collection, e.getMessage());
        }
    }

    private int resolveRetentionDays() {
        if (props.purge() != null && props.purge().retentionDays() > 0) {
            return props.purge().retentionDays();
        }
        return 100;
    }
}
