package com.init.worker.worker;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.init.worker.config.RagProperties;
import com.init.worker.domain.RagDocument;
import com.init.worker.domain.RagDocumentFile;
import com.init.worker.domain.RagDocumentJob;
import com.init.worker.domain.RagEntity;
import com.init.worker.domain.enums.ChunkMode;
import com.init.worker.domain.enums.RagJobStep;
import com.init.worker.dto.pcc.PccChunkHints;
import com.init.worker.dto.pcc.PccChunkRequest;
import com.init.worker.dto.pcc.PccIngestRequest;
import com.init.worker.dto.pcc.PccIngestResponse;
import com.init.worker.dto.pcc.PccParseCleanResponse;
import com.init.worker.error.code.ErrorCodes;
import com.init.worker.error.exception.AppException;
import com.init.worker.langchain.LangchainPccClient;
import com.init.worker.prompt.GraphPromptTexts;
import com.init.worker.repo.RagDocumentJobRepository;
import com.init.worker.repo.RagDocumentRepository;
import com.init.worker.repo.RagEntityChunkRepository;
import com.init.worker.repo.RagEntityRelationRepository;
import com.init.worker.repo.RagEntityRepository;
import com.init.worker.service.EmbeddingApiKeyResolver;
import com.init.worker.service.EntityLinkCache;
import com.init.worker.service.RagChunkWriter;
import com.init.worker.service.RagDocumentLoader;
import com.init.worker.service.RagGraphExtractionClient;
import com.init.worker.service.RagGraphVocabularyService;
import com.init.worker.service.RagJobStateMachine;
import com.init.worker.service.RagJobStepTimingRecorder;
import com.init.worker.storage.PathPolicy;
import com.init.worker.storage.StorageClient;
import com.init.worker.util.EntityNameNormalizer;
import com.init.worker.util.IdGenerator;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/**
 * Graph RAG Pass1(EXTRACT_ENTITY) 워커 — graph.enabled 시 PCC 를 대체하는 파이프라인 진입 단계.
 *
 * <p>한 번의 실행에서 parse·clean → 엔티티 사전 추출 → 정리본 청킹 → 청크 저장까지 수행하고 EMBED 로 넘긴다.
 * 정리본(cleaned text)은 MinIO 에 저장하지 않고 in-memory 로만 다루며, 청킹된 결과와 원본 파일만 저장된다.
 * <ul>
 *   <li>문서 전체 정리본을 LLM 에 한 번 보내 canonical 엔티티 사전을 만든다({@code rag_entity}).</li>
 *   <li>확정 엔티티를 검색측 엔티티 링킹용 Redis {@code entity:link} 캐시로 선워밍한다.</li>
 *   <li>브리지·관계는 Pass2({@code EXTRACT_RELATION})가 UPSERT 후 채운다.</li>
 * </ul>
 * 비차단: 엔티티 추출(LLM/UAK) 실패는 벡터 RAG 를 막지 않는다 — 엔티티를 건너뛰고 정리본 청킹으로 진행해
 * EMBED 로 넘긴다(그래프만 degrade). parse·clean/청킹 실패만 파이프라인 실패로 다룬다.
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "rag.app.role", havingValue = "consumer")
public class RagGraphEntityWorker extends AbstractRagStepWorker {

    private static final String WORKER_ID = "rag-graph-entity-worker";

    private final RagProperties props;
    private final LangchainPccClient langchainPccClient;
    private final RagChunkWriter chunkWriter;
    private final RagEntityRepository entityRepo;
    private final RagEntityRelationRepository relationRepo;
    private final RagEntityChunkRepository entityChunkRepo;
    private final RagGraphExtractionClient extractionClient;
    private final EmbeddingApiKeyResolver apiKeyResolver;
    private final EntityLinkCache entityLinkCache;
    private final RagJobStepTimingRecorder stepTimingRecorder;
    private final RagGraphVocabularyService vocabService;

    private final ObjectMapper om = new ObjectMapper();

    public RagGraphEntityWorker(
            RagProperties props,
            RagDocumentRepository docRepo,
            RagDocumentJobRepository jobRepo,
            RagDocumentLoader documentLoader,
            PathPolicy pathPolicy,
            StorageClient storageClient,
            RagJobStateMachine ragJobStateMachine,
            LangchainPccClient langchainPccClient,
            RagChunkWriter chunkWriter,
            RagEntityRepository entityRepo,
            RagEntityRelationRepository relationRepo,
            RagEntityChunkRepository entityChunkRepo,
            RagGraphExtractionClient extractionClient,
            EmbeddingApiKeyResolver apiKeyResolver,
            EntityLinkCache entityLinkCache,
            RagJobStepTimingRecorder stepTimingRecorder,
            RagGraphVocabularyService vocabService
    ) {
        super(docRepo, jobRepo, documentLoader, pathPolicy, storageClient, ragJobStateMachine, WORKER_ID);
        this.props = props;
        this.langchainPccClient = langchainPccClient;
        this.chunkWriter = chunkWriter;
        this.entityRepo = entityRepo;
        this.relationRepo = relationRepo;
        this.entityChunkRepo = entityChunkRepo;
        this.extractionClient = extractionClient;
        this.apiKeyResolver = apiKeyResolver;
        this.entityLinkCache = entityLinkCache;
        this.stepTimingRecorder = stepTimingRecorder;
        this.vocabService = vocabService;
    }

    @Override
    protected RagJobStep step() {
        return RagJobStep.EXTRACT_ENTITY;
    }

    @Override
    protected String logPrefix() {
        return "RAG_ENTITY";
    }

    public void handle(RagDocumentJob job) {
        final String traceId = clientTransactionId(job);
        final RagDocumentLoader.WithFile loaded;
        try {
            loaded = loadWithFileOrThrow(job);
        } catch (Exception e) {
            failWithoutDoc(job, "LOAD_FAILED", "doc/file load error: " + e.getMessage(), e);
            return;
        }
        RagDocument doc = loaded.doc();
        RagDocumentFile file = loaded.file();
        log.info("[{}][{}] handle start. jobId={} docId={} categoryId={} userNo={} fileSize={}",
                logPrefix(), traceId, job.getId(), doc.getId(),
                doc.getCategoryId(), doc.getUserNo(), file.getFileSize());

        try {
            String storageKey = file.getStorageKey();
            if (storageKey == null || storageKey.isBlank()) {
                failure(job, doc, "STORAGE_KEY_EMPTY", "file storageKey is empty", null);
                return;
            }

            int exp = props.pcc().presignExpirySeconds();
            String objectUrl = storageClient.presignedGetUrl(storageKey, exp, traceId);

            RagProperties.Chunk c = props.chunk();
            PccChunkHints hints = new PccChunkHints(c.maxChars(), c.overlapChars(), c.minChars(), c.mode());

            String embeddingApiKey = (c.mode() == ChunkMode.SEMANTIC)
                    ? apiKeyResolver.resolve(doc)
                    : null;

            String fileName = file.getOriginalFileName() == null ? "original" : file.getOriginalFileName();
            String mime = guessMimeType(fileName);

            PccIngestRequest req = new PccIngestRequest(
                    objectUrl,
                    mime,
                    fileName,
                    doc.getId().toString(),
                    doc.getCategoryId() != null ? doc.getCategoryId().toString() : null,
                    job.getId().toString(),
                    hints,
                    embeddingApiKey
            );

            // 재실행 idempotency: 이전 청크/임베딩 산출물과 그래프(관계·브리지·엔티티)를 문서 단위로 초기화한다.
            chunkWriter.clearPriorArtifacts(doc.getId(), traceId, logPrefix());
            relationRepo.deleteByDocumentId(doc.getId());
            entityChunkRepo.deleteByDocumentId(doc.getId());
            entityRepo.deleteByDocumentId(doc.getId());

            // parse·clean — 정리본 전체 텍스트(저장하지 않고 in-memory 로만 사용)
            long pcStartedNs = System.nanoTime();
            PccParseCleanResponse pc = langchainPccClient.parseClean(req, traceId, doc.getUserNo());
            long pcWallMs = Math.max(0L, (System.nanoTime() - pcStartedNs) / 1_000_000L);
            String cleaned = pc == null ? null : pc.text();
            if (cleaned == null || cleaned.isBlank()) {
                failure(job, doc, "PC_EMPTY", "parse-clean returned empty text", null);
                return;
            }
            long parseMs = stageMs(pc == null ? null : pc.timings(), true, false);
            long cleanMs = stageMs(pc == null ? null : pc.timings(), false, true);
            if (parseMs == 0L && cleanMs == 0L && pcWallMs > 0L) {
                // 하위 타이밍 미제공 시 parse-clean wall-clock 을 파싱에 귀속
                parseMs = pcWallMs;
            }

            // Pass1 엔티티 사전 추출 + 무손실 정리본 재작성(비차단: 실패하면 null → 원문으로 폴백 청킹)
            long entityStartedNs = System.nanoTime();
            String reorganized = extractEntitiesAndReorganize(doc, file, cleaned, job, traceId);
            long entityMs = Math.max(0L, (System.nanoTime() - entityStartedNs) / 1_000_000L);
            boolean usedReorganized = reorganized != null && !reorganized.isBlank();
            String textToChunk = usedReorganized ? reorganized : cleaned;

            // 정리본(실패 시 parse-clean 원문) 청킹 → 청크 저장
            long chunkStartedNs = System.nanoTime();
            PccIngestResponse resp = langchainPccClient.chunk(new PccChunkRequest(textToChunk, hints), traceId);
            long chunkWallMs = Math.max(0L, (System.nanoTime() - chunkStartedNs) / 1_000_000L);
            if (resp == null || resp.chunks() == null || resp.chunks().isEmpty()) {
                failure(job, doc, "CHUNK_EMPTY", "langchain chunk returned no chunks", null);
                return;
            }
            long chunkMs = stageChunkMs(resp.timings());
            if (chunkMs == 0L) {
                chunkMs = chunkWallMs;
            }
            int stored = chunkWriter.store(doc, file, resp.chunks(), traceId);
            if (stored == 0) {
                failure(job, doc, "CHUNK_NO_TEXT", "all chunk texts were blank", null);
                return;
            }

            // PARSE/CLEAN/CHUNK 각각 저장 + 엔티티 LLM 은 EXTRACT_ENTITY 로 별도 기록
            stepTimingRecorder.completeParseCleanChunk(
                    job.getId(),
                    doc.getId(),
                    new PccIngestResponse.PccStageTimings(parseMs, cleanMs, chunkMs)
            );
            stepTimingRecorder.recordCompleted(job.getId(), doc.getId(), RagJobStep.EXTRACT_ENTITY, entityMs);
            success(job, RagJobStep.EMBED);
            log.info("[{}][{}] entity phase ok. chunkCount={} chunkedFrom={} parseMs={} cleanMs={} chunkMs={} entityMs={}",
                    logPrefix(), traceId, stored, usedReorganized ? "reorganized" : "parse-clean(fallback)",
                    parseMs, cleanMs, chunkMs, entityMs);

        } catch (AppException e) {
            if (e.getErrorCode() == ErrorCodes.Api.UNSUPPORTED_FILE_FORMAT) {
                log.warn("[{}][{}] unsupported file format, permanent failure", logPrefix(), traceId, e);
                failure(job, doc, e.getCode(), e.getMessage(), e);
            } else {
                log.warn("[{}][{}] app exception -> requeue", logPrefix(), traceId, e);
                retryLater(job, "ENTITY_EXCEPTION", "entity phase exception: " + e.getMessage(), e);
            }
        } catch (Exception e) {
            log.warn("[{}][{}] exception -> requeue", logPrefix(), traceId, e);
            retryLater(job, "ENTITY_EXCEPTION", "entity phase exception: " + e.getMessage(), e);
        }
    }

    /**
     * 원문에서 canonical 엔티티 사전을 뽑아 저장·캐싱하고, 무손실 정리본(엔티티 지칭 정규화)을 재작성해 반환한다.
     * 반환값은 청킹 대상 정리본이며, 비차단: 어떤 실패도 삼키고 {@code null} 을 반환해 호출부가 parse-clean
     * 원문으로 폴백 청킹하도록 한다 — 벡터 RAG 는 정상 진행되고 그래프 품질만 degrade 된다.
     */
    private String extractEntitiesAndReorganize(RagDocument doc, RagDocumentFile file, String cleaned,
                                                RagDocumentJob job, String traceId) {
        final String apiKey;
        try {
            apiKey = apiKeyResolver.resolve(doc);
        } catch (Exception e) {
            log.warn("[{}][{}] skip entity extraction (no api key, graph degraded). err={}",
                    logPrefix(), traceId, e.toString());
            return null;
        }

        RagProperties.Graph graph = props.graph();
        Long fileSize = file.getFileSize();
        String aiServiceName = graph.resolveAiServiceName(fileSize);
        String modelPreference = graph.resolveModelPreference(fileSize);
        String modelCode = graph.resolveModelCode(fileSize);

        try {
            String prompt = GraphPromptTexts.buildEntityDictionaryPrompt(cleaned, vocabService.getEntityTypes());
            String raw = extractionClient.complete(
                    aiServiceName, modelPreference, modelCode, prompt, apiKey, job.getTransactionId());

            JsonNode root = parseJson(raw);
            if (root == null) {
                log.warn("[{}][{}] entity LLM 응답 JSON 파싱 불가 — 엔티티·정리본 건너뜀(원문 폴백 청킹). rawLen={} rawHead={}",
                        logPrefix(), traceId,
                        raw == null ? 0 : raw.length(),
                        raw == null ? "" : raw.substring(0, Math.min(120, raw.length())));
                return null;
            }
            int created = persistEntities(doc.getId(), root.path("entities"));
            String reorganized = text(root, "cleaned_document");
            log.info("[{}][{}] entity dictionary built. entities={} reorganizedChars={} aiService={} preference={} modelCode={}",
                    logPrefix(), traceId, created, reorganized == null ? 0 : reorganized.length(),
                    aiServiceName, modelPreference, modelCode);
            return (reorganized == null || reorganized.isBlank()) ? null : reorganized;
        } catch (Exception e) {
            String kind = isTimeoutLike(e) ? "TIMEOUT" : e.getClass().getSimpleName();
            log.warn("[{}][{}] entity extraction failed (ignored, 원문 폴백 청킹). kind={} err={}",
                    logPrefix(), traceId, kind, e.toString());
            return null;
        }
    }

    private static boolean isTimeoutLike(Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t.getClass().getSimpleName().contains("Timeout")) {
                return true;
            }
        }
        return false;
    }

    /** entities 노드를 (type,name) dedup 으로 저장하고 entity:link 캐시를 선워밍한다. 신규 저장 수 반환. */
    private int persistEntities(UUID documentId, JsonNode entities) {
        if (entities == null || !entities.isArray()) {
            return 0;
        }
        Set<String> seen = new HashSet<>();
        int created = 0;
        for (JsonNode en : entities) {
            String name = text(en, "name");
            if (name == null || name.isBlank()) {
                continue;
            }
            name = name.trim();
            String type = vocabService.normalizeEntityType(text(en, "type"));
            if (!seen.add(type + " " + EntityNameNormalizer.normalize(name))) {
                continue;
            }
            RagEntity entity = entityRepo.findByDocumentIdAndTypeAndName(documentId, type, name)
                    .orElse(null);
            if (entity == null) {
                entity = new RagEntity(IdGenerator.newId(), documentId, name, type);
                entityRepo.save(entity);
                created++;
            }
            entityLinkCache.prewarm(entity);
        }
        return created;
    }

    /** LLM 원문에서 코드펜스를 걷어내고 첫 JSON 오브젝트를 파싱한다. 실패 시 null. */
    private JsonNode parseJson(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String s = raw.trim();
        int start = s.indexOf('{');
        int end = s.lastIndexOf('}');
        if (start < 0 || end <= start) {
            return null;
        }
        try {
            return om.readTree(s.substring(start, end + 1));
        } catch (Exception e) {
            return null;
        }
    }

    private static String text(JsonNode node, String field) {
        JsonNode v = node.get(field);
        return (v == null || v.isNull()) ? null : v.asText();
    }

    private static String guessMimeType(String fileName) {
        if (fileName == null) {
            return "application/octet-stream";
        }
        String lower = fileName.toLowerCase();
        if (lower.endsWith(".pdf")) return "application/pdf";
        if (lower.endsWith(".txt")) return "text/plain";
        if (lower.endsWith(".md")) return "text/markdown";
        if (lower.endsWith(".html") || lower.endsWith(".htm")) return "text/html";
        if (lower.endsWith(".json")) return "application/json";
        if (lower.endsWith(".docx")) return "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
        if (lower.endsWith(".doc")) return "application/msword";
        if (lower.endsWith(".xlsx")) return "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
        if (lower.endsWith(".pptx")) return "application/vnd.openxmlformats-officedocument.presentationml.presentation";
        if (lower.endsWith(".hwp")) return "application/x-hwp";
        if (lower.endsWith(".hwpx")) return "application/hwp+zip";
        return "application/octet-stream";
    }

    private static long stageMs(PccIngestResponse.PccStageTimings timings, boolean parse, boolean clean) {
        if (timings == null) {
            return 0L;
        }
        if (parse) {
            return timings.parseMs() != null ? Math.max(0L, timings.parseMs()) : 0L;
        }
        if (clean) {
            return timings.cleanMs() != null ? Math.max(0L, timings.cleanMs()) : 0L;
        }
        return 0L;
    }

    private static long stageChunkMs(PccIngestResponse.PccStageTimings timings) {
        if (timings == null || timings.chunkMs() == null) {
            return 0L;
        }
        return Math.max(0L, timings.chunkMs());
    }
}
