package com.ragservice.worker.worker;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ragservice.worker.config.RagProperties;
import com.ragservice.worker.domain.RagDocument;
import com.ragservice.worker.domain.RagDocumentJob;
import com.ragservice.worker.domain.RagEmbeddingPart;
import com.ragservice.worker.domain.RagIndexMetadata;
import com.ragservice.worker.domain.enums.RagEmbeddingPartStatus;
import com.ragservice.worker.domain.enums.RagJobStep;
import com.ragservice.worker.qdrant.QdrantClient;
import com.ragservice.worker.qdrant.QdrantModels;
import com.ragservice.worker.repo.RagDocumentJobRepository;
import com.ragservice.worker.repo.RagDocumentRepository;
import com.ragservice.worker.repo.RagEmbeddingPartRepository;
import com.ragservice.worker.repo.RagIndexMetadataRepository;
import com.ragservice.worker.service.RagDocumentLoader;
import com.ragservice.worker.service.RagJobStateMachine;
import com.ragservice.worker.storage.PathPolicy;
import com.ragservice.worker.util.IdGenerator;
import com.ragservice.worker.storage.StorageClient;
import jakarta.transaction.Transactional;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import java.io.BufferedReader;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.util.*;

import static com.ragservice.worker.qdrant.QdrantModels.*;

/**
 * embeddings part 파일(JSONL)을 읽어 Qdrant에 벡터를 업서트하는 워커.
 *
 * - READY 상태의 rag_embedding_parts를 순서대로 처리한다.
 * - 필요 시 컬렉션 생성, 기존 문서 포인트 삭제(재색인 정책) 후 배치 업서트를 수행한다.
 * - 업서트 결과를 rag_index_metadata에 기록하고, 완료 시 문서를 INDEXED 상태로 전이한다.
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "rag.app.role", havingValue = "consumer")
public class RagUpsertWorker extends AbstractRagStepWorker {

    private final RagProperties props;
    private final QdrantClient qdrantClient;
    private final RagEmbeddingPartRepository partRepo;
    private final RagIndexMetadataRepository indexRepo;

    /** JSONL 파싱용 */
    private final ObjectMapper om = new ObjectMapper();

    /** 업서트 대상 Qdrant 설정 */
    private final String qdrantBaseUrl;
    private final String qdrantCollection;

    /** 한 번에 업서트할 포인트 수 */
    private final int upsertBatchSize = 64;

    public RagUpsertWorker(
            RagProperties props,
            RagDocumentRepository docRepo,
            RagDocumentJobRepository jobRepo,
            RagDocumentLoader documentLoader,
            PathPolicy pathPolicy,
            StorageClient storageClient,
            RagJobStateMachine ragJobStateMachine,
            QdrantClient qdrantClient,
            RagEmbeddingPartRepository partRepo,
            RagIndexMetadataRepository indexRepo,
            @Value("${rag.qdrant.base-url}") String qdrantBaseUrl,
            @Value("${rag.qdrant.collection}") String qdrantCollection
    ) {
        super(docRepo, jobRepo, documentLoader, pathPolicy, storageClient, ragJobStateMachine, "rag-upsert-worker");
        this.props = props;
        this.qdrantClient = qdrantClient;
        this.partRepo = partRepo;
        this.indexRepo = indexRepo;

        if (!StringUtils.hasText(qdrantBaseUrl)) throw new IllegalStateException("rag.qdrant.base-url is required");
        if (!StringUtils.hasText(qdrantCollection)) throw new IllegalStateException("rag.qdrant.collection is required");

        this.qdrantBaseUrl = qdrantBaseUrl.trim();
        this.qdrantCollection = qdrantCollection.trim();

        log.info("[{}][BOOT] qdrantBaseUrl={}, qdrantCollection={}", logPrefix(), this.qdrantBaseUrl, this.qdrantCollection);
    }

    @Override protected RagJobStep step() { return RagJobStep.UPSERT; }
    @Override protected String logPrefix() { return "RAG_UPSERT"; }

    /**
     * UPSERT step 처리.
     *
     * 흐름:
     * 1) READY/UPSERTED/FAILED 파트 상태 점검
     * 2) 필요 시 Qdrant 컬렉션 ensure
     * 3) 재색인 정책에 따라 deleteByDocument 수행 가능
     * 4) READY part를 순서대로 읽어 Qdrant batch upsert
     * 5) part 상태를 UPSERTED 또는 FAILED로 반영
     * 6) rag_index_metadata 저장
     * 7) 성공 시 terminal success 처리
     */
    @Transactional
    public void handle(RagDocumentJob job) {
        String traceId = clientTransactionId(job);
        final RagDocumentLoader.WithFile loaded;
        try {
            loaded = loadWithFileOrThrow(job);
        } catch (Exception e) {
            log.warn("[{}][{}] failed to load doc/file for job", logPrefix(), job.getId(), e);
            failWithoutDoc(job, "LOAD_FAILED", "doc/file load error: " + e.getMessage(), e);
            return;
        }
        RagDocument doc = loaded.doc();

        try {
            log.info("[{}][{}] handle start. jobId={}, docId={}, qdrantBaseUrl={}, qdrantCollection={}",
                    logPrefix(), traceId, job.getId(), doc.getId(), qdrantBaseUrl, qdrantCollection);

            boolean ensureCollection = props.worker() != null && Boolean.TRUE.equals(props.worker().ensureCollection());
            log.debug("[{}][{}] ensureCollection={}", logPrefix(), traceId, ensureCollection);
            if (ensureCollection) {
                int dim = (props.embedding() != null && props.embedding().dimension() != null
                        && props.embedding().dimension() > 0)
                        ? props.embedding().dimension()
                        : 1536;
                qdrantClient.ensureCollection(qdrantBaseUrl, qdrantCollection, dim);
            }

            partRepo.deletePartsWithMissingChunk();

            long readyCnt = partRepo.countByDocumentIdAndStatus(doc.getId(), RagEmbeddingPartStatus.READY);
            long upsertedCnt = partRepo.countByDocumentIdAndStatus(doc.getId(), RagEmbeddingPartStatus.UPSERTED);
            long failedCnt = partRepo.countByDocumentIdAndStatus(doc.getId(), RagEmbeddingPartStatus.FAILED);

            // 이전 UPSERT 실패 등으로 남은 FAILED 파트 — READY 가 있으면 정리 후 진행
            if (failedCnt > 0 && readyCnt > 0) {
                int cleared = partRepo.deleteByDocumentIdAndStatus(doc.getId(), RagEmbeddingPartStatus.FAILED);
                log.warn("[{}][{}] cleared stale FAILED embedding parts. docId={}, cleared={}",
                        logPrefix(), traceId, doc.getId(), cleared);
                failedCnt = 0;
            }

            if (failedCnt > 0) {
                failure(job, doc,"EMBEDDING_PART_FAILED_EXISTS",
                        "embedding parts already FAILED. failedCnt=" + failedCnt, null);
                return;
            }

            // READY가 없을 때의 분기:
            // - UPSERTED가 이미 있으면 재실행/중복 처리로 보고 성공 처리
            // - 둘 다 없으면 실패
            if (readyCnt == 0) {
                if (upsertedCnt > 0) {
                    log.info("[{}][{}] no READY parts, UPSERTED exists. upsertedCnt={}, ensure index metadata",
                            logPrefix(), traceId, upsertedCnt);
                    saveIndexMetadata(doc);
                    ragJobStateMachine.onUpsertSuccess(job, props.graph().enabled());
                    return;
                }
                retryLater(job, "EMBEDDING_PART_NOT_READY",
                        "no embedding parts yet (READY=0, UPSERTED=0) — waiting for embed step", null);
                return;
            }

            String policy = (props.worker() != null && StringUtils.hasText(props.worker().reindexPolicy()))
                    ? props.worker().reindexPolicy().trim()
                    : "OVERWRITE";

            // 재인덱싱 정책이 DELETE_THEN_UPSERT면 기존 문서 포인트 삭제
            if ("DELETE_THEN_UPSERT".equalsIgnoreCase(policy)) {
                log.debug("[{}][{}] reindexPolicy=DELETE_THEN_UPSERT. deleteByDocument document_id={}",
                        logPrefix(), traceId, doc.getDocumentId());
                qdrantClient.deleteByDocument(qdrantBaseUrl, qdrantCollection, doc.getId().toString(), traceId);
            } else {
                log.debug("[{}][{}] reindexPolicy=OVERWRITE (default).", logPrefix(), traceId);
            }

            long totalPoints = 0;

            String pathCategory = documentLoader.pathCategory(loaded.file());

            while (true) {
                Optional<RagEmbeddingPart> opt = partRepo.findFirstByDocAndStatus(
                        doc.getId(), RagEmbeddingPartStatus.READY
                );
                if (opt.isEmpty()) break;

                RagEmbeddingPart part = opt.get();
                String embeddingsKey = pathPolicy.buildEmbeddingChunkKey(
                        pathCategory, doc.getId().toString(), part.getChunkId().toString());

                try {
                    byte[] bytes = storageClient.downloadBytes(embeddingsKey, traceId);
                    String jsonl = new String(bytes, StandardCharsets.UTF_8);

                    List<Point> points = parseJsonlToPoints(jsonl, pathCategory, doc.getId().toString(), traceId);

                    long upserted = 0;
                    if (!points.isEmpty()) {
                        upserted = upsertInBatches(points, traceId);
                        totalPoints += upserted;
                    }

                    part.markUpserted(upserted);
                    partRepo.save(part);

                    log.debug("[{}][{}] part upserted. chunkId={}, points={}, key={}",
                            logPrefix(), traceId, part.getChunkId(), upserted, embeddingsKey);

                } catch (Exception e) {
                    part.markFailed();
                    partRepo.save(part);
                    throw e;
                }
            }

            long failedAfter = partRepo.countByDocumentIdAndStatus(doc.getId(), RagEmbeddingPartStatus.FAILED);
            if (failedAfter > 0) {
                throw new IllegalStateException("embedding parts failed after upsert. failedCount=" + failedAfter);
            }

            saveIndexMetadata(doc);

            log.info("[{}][{}] upsert done. totalPoints={}", logPrefix(), traceId, totalPoints);

            ragJobStateMachine.onUpsertSuccess(job, props.graph().enabled());
            log.info("[{}][{}] handle success. vectorRAG indexed, graphEnabled={}",
                    logPrefix(), traceId, props.graph().enabled());

        } catch (WebClientResponseException e) {
            log.error("[{}][{}] qdrant error. status={}, body={}",
                    logPrefix(), traceId, e.getStatusCode(), e.getResponseBodyAsString());
            if (e.getStatusCode().is5xxServerError()
                    || e.getStatusCode().value() == 429
                    || e.getStatusCode().value() == 408) {
                clearStaleFailedParts(doc);
                retryLater(job, "QDRANT_TEMPORARY_ERROR", "qdrant transient error: " + e.getResponseBodyAsString(), e);
                return;
            }
            failure(job, doc,"QDRANT_BAD_REQUEST", "qdrant error: " + e.getResponseBodyAsString(), e);

        } catch (Exception e) {
            log.warn("[{}][{}] exception", logPrefix(), traceId, e);
            clearStaleFailedParts(doc);
            retryLater(job, "UPSERT_EXCEPTION", "upsert exception: " + e.getMessage(), e);
        }
    }

    private void clearStaleFailedParts(RagDocument doc) {
        int cleared = partRepo.deleteByDocumentIdAndStatus(doc.getId(), RagEmbeddingPartStatus.FAILED);
        if (cleared > 0) {
            log.info("[{}] cleared FAILED embedding parts before upsert retry. docId={}, cleared={}",
                    logPrefix(), doc.getId(), cleared);
        }
    }

    /** 문서별 인덱싱 결과 메타 저장/갱신 */
    private void saveIndexMetadata(RagDocument doc) {
        final String model;
        final Integer dim;
        if (props.embedding() != null) {
            model = (props.embedding().provider() != null && !props.embedding().provider().isBlank())
                    ? props.embedding().provider()
                    : null;
            dim = (props.embedding().dimension() != null && props.embedding().dimension() > 0)
                    ? props.embedding().dimension()
                    : null;
        } else {
            model = null;
            dim = null;
        }

        RagIndexMetadata meta = indexRepo.findByDocumentId(doc.getId())
                .orElseGet(() -> new RagIndexMetadata(
                        IdGenerator.newId(),
                        doc.getId(),
                        qdrantCollection,
                        model,
                        dim
                ));
        meta.refreshIndexedAt();
        indexRepo.save(meta);
    }

    /**
     * embeddings JSONL을 Qdrant Point 목록으로 변환.
     * - id는 chunkId를 기반으로 Qdrant용 UUID로 변환
     * - payload에는 chunk_id를 보강해서 저장
     * - storage_key로 MinIO에서 청크 원문을 읽어 page_content 필드로 포함
     *   (LangChain QdrantVectorStore가 page_content를 Document 텍스트로 사용)
     */
    private List<Point> parseJsonlToPoints(String jsonl,
                                           String pathCategory,
                                           String documentId,
                                           String traceId) throws Exception {
        List<Point> out = new ArrayList<>();

        try (BufferedReader br = new BufferedReader(new StringReader(jsonl))) {
            String line;
            while ((line = br.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty()) continue;

                JsonNode n = om.readTree(line);

                String chunkId = n.get("id").asText();
                String qdrantId = UUID.nameUUIDFromBytes(chunkId.getBytes(StandardCharsets.UTF_8)).toString();

                JsonNode vecNode = n.get("vector");
                if (vecNode == null || !vecNode.isArray()) throw new IllegalStateException("invalid vector jsonl line");

                List<Float> vector = new ArrayList<>(vecNode.size());
                for (JsonNode v : vecNode) vector.add(v.floatValue());

                Map<String, Object> payload = new LinkedHashMap<>();
                JsonNode payloadNode = n.get("payload");
                if (payloadNode != null && payloadNode.isObject()) {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> converted = om.convertValue(payloadNode, Map.class);
                    payload = converted;
                }
                payload.putIfAbsent("chunk_id", chunkId);

                int chunkIndex = payload.get("chunk_index") instanceof Number num
                        ? num.intValue()
                        : -1;
                String chunkStorageKey = chunkIndex >= 0
                        ? pathPolicy.buildChunkKey(pathCategory, documentId, chunkIndex)
                        : null;
                if (chunkStorageKey == null) {
                    Object storageKeyObj = payload.get("storage_key");
                    if (storageKeyObj instanceof String legacyKey && !legacyKey.isBlank()) {
                        chunkStorageKey = legacyKey;
                    }
                }
                if (chunkStorageKey != null) {
                    payload.put("storage_key", chunkStorageKey);
                    try {
                        byte[] chunkBytes = storageClient.downloadBytes(chunkStorageKey, traceId);
                        if (chunkBytes != null && chunkBytes.length > 0) {
                            String text = new String(chunkBytes, StandardCharsets.UTF_8)
                                    .replace(String.valueOf((char) 0), "");
                            payload.put("page_content", text);
                        }
                    } catch (Exception e) {
                        log.warn("[{}][{}] page_content 로드 실패, storage_key={}: {}",
                                logPrefix(), traceId, chunkStorageKey, e.getMessage());
                    }
                }

                out.add(new QdrantModels.Point(qdrantId, vector, payload));
            }
        }

        return out;
    }

    /** 포인트 목록을 upsertBatchSize 단위로 나눠 Qdrant에 업서트 */
    private long upsertInBatches(List<Point> points, String traceId) {
        long total = 0;

        for (int i = 0; i < points.size(); i += upsertBatchSize) {
            int end = Math.min(i + upsertBatchSize, points.size());
            List<Point> batch = points.subList(i, end);

            UpsertRequest req = new UpsertRequest(batch);
            qdrantClient.upsert(qdrantBaseUrl, qdrantCollection, req, traceId);

            total += batch.size();
            log.debug("[{}][{}] upsert batch ok. range=[{}..{})", logPrefix(), traceId, i, end);
        }

        return total;
    }
}
