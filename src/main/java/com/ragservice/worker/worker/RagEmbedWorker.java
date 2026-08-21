package com.ragservice.worker.worker;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ragservice.worker.config.RagProperties;
import com.ragservice.worker.domain.RagChunk;
import com.ragservice.worker.domain.RagDocument;
import com.ragservice.worker.domain.RagDocumentJob;
import com.ragservice.worker.domain.RagEmbeddingPart;
import com.ragservice.worker.domain.enums.RagEmbeddingPartStatus;
import com.ragservice.worker.domain.enums.RagJobStep;
import com.ragservice.worker.dto.EmbeddingInput;
import com.ragservice.worker.repo.RagChunkRepository;
import com.ragservice.worker.repo.RagDocumentJobRepository;
import com.ragservice.worker.repo.RagDocumentRepository;
import com.ragservice.worker.repo.RagEmbeddingPartRepository;
import com.ragservice.worker.service.*;
import com.ragservice.worker.storage.PathPolicy;
import com.ragservice.worker.storage.StorageClient;
import com.ragservice.worker.util.IdGenerator;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * rag_chunks 를 읽어 임베딩을 생성하고, 청크별 JSONL + rag_embedding_parts(READY) 를 기록한다.
 */
@Service
@Slf4j
@ConditionalOnProperty(name = "rag.app.role", havingValue = "consumer")
public class RagEmbedWorker extends AbstractRagStepWorker {

    private static final String WORKER_ID = "rag-embed-worker";

    private final RagChunkRepository chunkRepo;
    private final RagProperties props;
    private final ObjectMapper om = new ObjectMapper();
    private final RagEmbeddingPartRepository partRepo;
    private final RagEmbedPersistence embedPersistence;
    private final AiEmbeddingClient aiEmbeddingClient;
    private final EmbeddingApiKeyResolver embeddingApiKeyResolver;

    @Value("${rag.embedding.ai-service-name:openai}")
    private String embeddingAiServiceName;

    public RagEmbedWorker(
            RagProperties props,
            RagDocumentRepository docRepo,
            RagDocumentJobRepository jobRepo,
            RagDocumentLoader documentLoader,
            PathPolicy pathPolicy,
            StorageClient storageClient,
            RagJobStateMachine ragJobStateMachine,
            RagChunkRepository chunkRepo,
            AiEmbeddingClient aiEmbeddingClient,
            RagEmbeddingPartRepository partRepo,
            RagEmbedPersistence embedPersistence,
            EmbeddingApiKeyResolver embeddingApiKeyResolver) {
        super(docRepo, jobRepo, documentLoader, pathPolicy, storageClient, ragJobStateMachine, WORKER_ID);
        this.aiEmbeddingClient = aiEmbeddingClient;
        this.chunkRepo = chunkRepo;
        this.props = props;
        this.partRepo = partRepo;
        this.embedPersistence = embedPersistence;
        this.embeddingApiKeyResolver = embeddingApiKeyResolver;
    }

    @Override
    protected RagJobStep step() {
        return RagJobStep.EMBED;
    }

    @Override
    protected String logPrefix() {
        return "RAG_EMBED";
    }

    public void handle(RagDocumentJob job) {
        final String traceId = clientTransactionId(job);
        final RagDocumentLoader.WithFile loaded;
        try {
            loaded = loadWithFileOrThrow(job);
        } catch (Exception e) {
            log.warn("[{}][{}] failed to load doc/file for job", logPrefix(), job.getId(), e);
            failWithoutDoc(job, "LOAD_FAILED", "doc/file load error: " + e.getMessage(), e);
            return;
        }
        RagDocument doc = loaded.doc();
        String fileChecksum = loaded.file().getChecksum();

        try {
            embedPersistence.clearEmbeddingPartsForDocument(doc.getId());

            int embedBatchSize = 64;
            if (props.worker() != null && props.worker().embedBatchSize() != null
                    && props.worker().embedBatchSize() > 0) {
                embedBatchSize = props.worker().embedBatchSize();
            }

            Integer expectedDim = (props.embedding() != null && props.embedding().dimension() != null
                    && props.embedding().dimension() > 0)
                    ? props.embedding().dimension()
                    : null;

            int pageSize = (props.worker() != null && props.worker().chunkPageSize() != null
                    && props.worker().chunkPageSize() > 0)
                    ? props.worker().chunkPageSize()
                    : 500;

            long total = chunkRepo.countByDoc(doc.getId());
            log.info("[RAG_EMBED][{}] handle start. jobId={} docId={} txId={} aiServiceName={} expectedDim={} embedBatchSize={} pageSize={} totalChunks={}",
                    traceId,
                    job.getId(),
                    doc.getId(),
                    job.getTransactionId(),
                    embeddingAiServiceName,
                    expectedDim,
                    embedBatchSize,
                    pageSize,
                    total);
            if (total <= 0) {
                retryLater(job, "CHUNK_META_NOT_READY", "rag_chunks rows not found yet — waiting for PCC", null);
                return;
            }

            final String apiKey;
            try {
                apiKey = embeddingApiKeyResolver.resolve(doc);
            } catch (EmbeddingApiKeyResolver.EmbeddingApiKeyResolveException e) {
                failure(job, doc, e.getCode(), e.getMessage(), e);
                return;
            }
            log.debug("[RAG_EMBED][{}] embedding api key resolved. docId={} userType={} userNo={}",
                    traceId, doc.getId(), doc.getUserType(), doc.getUserNo());
            String pathCategory = documentLoader.pathCategory(loaded.file());

            if (!documentLoader.allChunkStorageKeysAligned(doc.getId(), loaded.file())) {
                int reconciled = reconcileMisalignedChunks(doc, loaded.file(), traceId);
                if (reconciled > 0) {
                    log.info("[{}][{}] reconciled {} chunk storage_key(s) to match file.storage_key. docId={}",
                            logPrefix(), traceId, reconciled, doc.getId());
                }
            }
            if (!documentLoader.allChunkStorageKeysAligned(doc.getId(), loaded.file())) {
                documentLoader.findFirstMisalignedChunk(doc.getId(), loaded.file()).ifPresent(chunk -> {
                    String expected = documentLoader.canonicalChunkStorageKey(
                            loaded.file(), doc.getId(), chunk.getChunkIndex());
                    log.warn("[{}][{}] chunk storage_key still misaligned — reset to PCC. docId={}, "
                                    + "chunkIndex={}, dbKey={}, expectedKey={}, fileKey={}",
                            logPrefix(), traceId, doc.getId(), chunk.getChunkIndex(),
                            chunk.getStorageKey(), expected, loaded.file().getStorageKey());
                });
                embedPersistence.resetForPccRetry(doc.getId());
                ragJobStateMachine.onPccRetryReset(job, doc);
                return;
            }

            int fromIndex = 0;
            while (true) {
                List<RagChunk> page = chunkRepo.findPageByDoc(doc.getId(), fromIndex, PageRequest.of(0, pageSize));
                if (page == null || page.isEmpty()) {
                    break;
                }

                fromIndex = page.get(page.size() - 1).getChunkIndex() + 1;

                for (int start = 0; start < page.size(); start += embedBatchSize) {
                    int end = Math.min(page.size(), start + embedBatchSize);
                    List<RagChunk> batch = page.subList(start, end);

                    List<EmbeddingInput> inputs = new ArrayList<>(batch.size());
                    for (RagChunk c : batch) {
                        byte[] b = readChunkBytes(doc, loaded.file(), c, traceId);
                        if (b == null || b.length == 0) {
                            String chunkKey = documentLoader.canonicalChunkStorageKey(
                                    loaded.file(), doc.getId(), c.getChunkIndex());
                            retryLater(job, "CHUNK_FILE_NOT_READY",
                                    "chunk file missing/empty (may be uploading): " + chunkKey, null);
                            return;
                        }
                        inputs.add(new EmbeddingInput(c.getId().toString(), new String(b, StandardCharsets.UTF_8)));
                    }

                        if (!batch.isEmpty()) {
                            log.debug("[RAG_EMBED][{}] embedding request. docId={} batchSize={} chunkIndexRange=[{}..{}]",
                                    traceId,
                                    doc.getId(),
                                    batch.size(),
                                    batch.get(0).getChunkIndex(),
                                    batch.get(batch.size() - 1).getChunkIndex());
                        }

                    List<float[]> vectors = aiEmbeddingClient.requestDocumentEmbeddings(
                            embeddingAiServiceName,
                            job.getTransactionId(),
                            inputs,
                            apiKey);

                    if (props.worker() != null && props.worker().embedInterBatchDelayMs() != null
                            && props.worker().embedInterBatchDelayMs() > 0) {
                        try {
                            Thread.sleep(props.worker().embedInterBatchDelayMs());
                        } catch (InterruptedException ie) {
                            Thread.currentThread().interrupt();
                            failure(job, doc,"EMBED_INTERRUPTED",
                                    "interrupted during embed inter-batch delay", ie);
                            return;
                        }
                    }

                    if (vectors == null || vectors.size() != batch.size()) {
                        failure(job, doc,"EMBED_SIZE_MISMATCH",
                                "vectors size mismatch. vectors=" + (vectors == null ? -1 : vectors.size())
                                        + ", chunks=" + batch.size(),
                                null);
                        return;
                    }

                    if (expectedDim != null) {
                        for (int i = 0; i < vectors.size(); i++) {
                            float[] v = vectors.get(i);
                            if (v == null || v.length != expectedDim) {
                                failure(job, doc,"EMBED_DIM_MISMATCH",
                                        "vector dim mismatch. expected=" + expectedDim
                                                + ", actual=" + (v == null ? -1 : v.length)
                                                + ", chunkId=" + batch.get(i).getId(),
                                        null);
                                return;
                            }
                        }
                    }

                    for (int i = 0; i < batch.size(); i++) {
                        RagChunk chunk = batch.get(i);
                        String canonicalChunkKey = documentLoader.canonicalChunkStorageKey(
                                loaded.file(), doc.getId(), chunk.getChunkIndex());
                        saveChunkEmbedding(
                                doc, pathCategory, chunk, canonicalChunkKey, vectors.get(i), fileChecksum, traceId);
                    }
                }
            }

            success(job, RagJobStep.UPSERT);

        } catch (Exception e) {
            log.warn("[{}][{}] exception", logPrefix(), traceId, e);
            AiEmbeddingClient.EmbeddingFailureDetail detail = AiEmbeddingClient.describeFailure(e);
            if (shouldRetryEmbedLater(e, detail)) {
                retryLater(job, detail.code(), detail.message(), e);
                return;
            }
            failure(job, doc, detail.code(), detail.message(), e);
        }
    }

    private static boolean shouldRetryEmbedLater(Exception e, AiEmbeddingClient.EmbeddingFailureDetail detail) {
        if ("EMBEDDING_API_REQUEST_FAILED".equals(detail.code())) {
            return AiEmbeddingClient.isTransientEmbeddingFailure(e);
        }
        if ("EMBEDDING_API_TIMEOUT".equals(detail.code())
                || "EMBEDDING_API_CONNECTION_FAILED".equals(detail.code())) {
            return true;
        }
        if (detail.code() != null && detail.code().startsWith("EMBEDDING_API_RESPONSE")) {
            return true;
        }
        if ("EMBED_EXCEPTION".equals(detail.code())) {
            if (AiEmbeddingClient.isTransientEmbeddingFailure(e)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 구형 prefix 등으로 DB/MinIO 청크 경로가 file.storage_key 와 어긋난 경우,
     * 기존 객체를 읽어 canonical 경로로 복사하고 rag_chunks 를 갱신한다.
     *
     * @return 정렬(reconcile)에 성공한 청크 수
     */
    private int reconcileMisalignedChunks(RagDocument doc,
                                          com.ragservice.worker.domain.RagDocumentFile file,
                                          String traceId) {
        int fixed = 0;
        int fromIndex = 0;
        int pageSize = 500;
        UUID documentId = doc.getId();
        while (true) {
            List<RagChunk> page = chunkRepo.findPageByDoc(documentId, fromIndex, PageRequest.of(0, pageSize));
            if (page == null || page.isEmpty()) {
                break;
            }
            for (RagChunk chunk : page) {
                if (documentLoader.isChunkStorageKeyAligned(file, documentId, chunk)) {
                    continue;
                }
                String canonical = documentLoader.canonicalChunkStorageKey(
                        file, documentId, chunk.getChunkIndex());
                byte[] bytes = readChunkBytes(doc, file, chunk, traceId);
                if (bytes == null || bytes.length == 0) {
                    continue;
                }
                if (!canonical.equals(chunk.getStorageKey())) {
                    storageClient.upload(canonical, bytes, "chunk.txt", traceId);
                }
                chunk.updateStorageKey(canonical);
                chunkRepo.save(chunk);
                fixed++;
            }
            fromIndex = page.get(page.size() - 1).getChunkIndex() + 1;
        }
        return fixed;
    }

    /** canonical → DB storage_key → 구형 categoryUuid 경로 순으로 MinIO 조회 */
    private byte[] readChunkBytes(RagDocument doc,
                                  com.ragservice.worker.domain.RagDocumentFile file,
                                  RagChunk chunk,
                                  String traceId) {
        String canonical = documentLoader.canonicalChunkStorageKey(
                file, doc.getId(), chunk.getChunkIndex());
        byte[] bytes = storageClient.downloadBytesIfPresent(canonical, traceId);
        if (bytes != null && bytes.length > 0) {
            return bytes;
        }
        String dbKey = chunk.getStorageKey();
        if (dbKey != null && !dbKey.isBlank() && !dbKey.equals(canonical)) {
            bytes = storageClient.downloadBytesIfPresent(dbKey, traceId);
            if (bytes != null && bytes.length > 0) {
                return bytes;
            }
        }
        if (doc.getCategoryId() != null) {
            String legacy = pathPolicy.legacyChunkStorageKey(
                    doc.getCategoryId().toString(), doc.getId().toString(), chunk.getChunkIndex());
            if (legacy != null && !legacy.equals(canonical) && !legacy.equals(dbKey)) {
                bytes = storageClient.downloadBytesIfPresent(legacy, traceId);
                if (bytes != null && bytes.length > 0) {
                    return bytes;
                }
            }
        }
        return null;
    }

    private void saveChunkEmbedding(RagDocument doc,
                                    String pathCategory,
                                    RagChunk chunk,
                                    String canonicalChunkStorageKey,
                                    float[] vector,
                                    String fileChecksum,
                                    String traceId) throws Exception {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("document_id", chunk.getDocumentId().toString());
        payload.put("chunk_index", chunk.getChunkIndex());
        if (fileChecksum != null && !fileChecksum.isBlank()) {
            payload.put("checksum", fileChecksum);
        }
        payload.put("storage_key", canonicalChunkStorageKey);
        payload.put("user_type", doc.getUserType().name());
        payload.put("user_no", doc.getUserNo().toString());
        if (doc.getCategoryId() != null) {
            payload.put("category_id", doc.getCategoryId().toString());
        }
        if (chunk.getLocation() != null && !chunk.getLocation().isBlank()) {
            payload.put("location", chunk.getLocation());
        }

        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", chunk.getId().toString());
        row.put("vector", vector);
        row.put("payload", payload);

        byte[] jsonlLine = om.writeValueAsBytes(row);
        byte[] withNewline = Arrays.copyOf(jsonlLine, jsonlLine.length + 1);
        withNewline[jsonlLine.length] = '\n';

        String key = pathPolicy.buildEmbeddingChunkKey(pathCategory, doc.getId().toString(), chunk.getId().toString());
        storageClient.upload(key, withNewline, "chunk-embed.jsonl", traceId);

        partRepo.save(new RagEmbeddingPart(
                IdGenerator.newId(),
                chunk.getId(),
                RagEmbeddingPartStatus.READY
        ));
    }
}
