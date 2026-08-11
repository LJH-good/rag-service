package com.init.worker.worker;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.init.worker.config.RagProperties;
import com.init.worker.domain.RagChunk;
import com.init.worker.domain.RagDocument;
import com.init.worker.domain.RagDocumentFile;
import com.init.worker.domain.RagDocumentJob;
import com.init.worker.domain.RagEntity;
import com.init.worker.domain.RagEntityChunk;
import com.init.worker.domain.RagEntityRelation;
import com.init.worker.domain.enums.RagJobStep;
import com.init.worker.prompt.GraphPromptTexts;
import com.init.worker.repo.RagChunkRepository;
import com.init.worker.repo.RagDocumentJobRepository;
import com.init.worker.repo.RagDocumentRepository;
import com.init.worker.repo.RagEntityChunkRepository;
import com.init.worker.repo.RagEntityRelationRepository;
import com.init.worker.repo.RagEntityRepository;
import com.init.worker.service.EmbeddingApiKeyResolver;
import com.init.worker.service.RagDocumentLoader;
import com.init.worker.service.RagGraphExtractionClient;
import com.init.worker.service.RagGraphVocabularyService;
import com.init.worker.service.RagJobStateMachine;
import com.init.worker.storage.PathPolicy;
import com.init.worker.storage.StorageClient;
import com.init.worker.util.EntityNameNormalizer;
import com.init.worker.util.IdGenerator;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Graph RAG Pass2(EXTRACT_RELATION) 워커.
 *
 * <p>UPSERT 완료(벡터 색인 종료) 후, 확정된 청크를 배치로 LLM 에 보내 엔티티+관계를 추출한다.
 * (Pass1 이 도입되기 전까지의 자기완결형 구현 — Pass1 도입 시 관계 전용으로 축소된다.)
 * <ul>
 *   <li>엔티티: {@code (document_id, type, name)} 기준 dedup upsert</li>
 *   <li>브리지: 엔티티가 등장한 청크로 {@code rag_entity_chunk} 연결(citation 환원용)</li>
 *   <li>관계: 엔티티 이름으로 src/dst 를 해소해 {@code rag_entity_relation} 저장</li>
 * </ul>
 * 비차단: 벡터 RAG 는 UPSERT 에서 이미 완료·INDEXED 이므로, Pass2 실패는 파일 상태를 강등하지 않고
 * best-effort 로 건너뛴다(설계 6절).
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "rag.app.role", havingValue = "consumer")
public class RagGraphWorker extends AbstractRagStepWorker {

    private static final String WORKER_ID = "rag-graph-worker";

    private final RagProperties props;
    private final RagChunkRepository chunkRepo;
    private final RagEntityRepository entityRepo;
    private final RagEntityRelationRepository relationRepo;
    private final RagEntityChunkRepository entityChunkRepo;
    private final RagGraphExtractionClient extractionClient;
    private final EmbeddingApiKeyResolver apiKeyResolver;
    private final RagGraphVocabularyService vocabService;

    private final ObjectMapper om = new ObjectMapper();

    public RagGraphWorker(
            RagProperties props,
            RagDocumentRepository docRepo,
            RagDocumentJobRepository jobRepo,
            RagDocumentLoader documentLoader,
            PathPolicy pathPolicy,
            StorageClient storageClient,
            RagJobStateMachine ragJobStateMachine,
            RagChunkRepository chunkRepo,
            RagEntityRepository entityRepo,
            RagEntityRelationRepository relationRepo,
            RagEntityChunkRepository entityChunkRepo,
            RagGraphExtractionClient extractionClient,
            EmbeddingApiKeyResolver apiKeyResolver,
            RagGraphVocabularyService vocabService
    ) {
        super(docRepo, jobRepo, documentLoader, pathPolicy, storageClient, ragJobStateMachine, WORKER_ID);
        this.props = props;
        this.chunkRepo = chunkRepo;
        this.entityRepo = entityRepo;
        this.relationRepo = relationRepo;
        this.entityChunkRepo = entityChunkRepo;
        this.extractionClient = extractionClient;
        this.apiKeyResolver = apiKeyResolver;
        this.vocabService = vocabService;
    }

    @Override
    protected RagJobStep step() {
        return RagJobStep.EXTRACT_RELATION;
    }

    @Override
    protected String logPrefix() {
        return "RAG_GRAPH";
    }

    public void handle(RagDocumentJob job) {
        final String traceId = clientTransactionId(job);
        final RagDocumentLoader.WithFile loaded;
        try {
            loaded = loadWithFileOrThrow(job);
        } catch (Exception e) {
            // doc/file 로드 실패라도 벡터 RAG 는 살아있으므로 그래프만 건너뛴다.
            ragJobStateMachine.onGraphSkip(job, "GRAPH_LOAD_FAILED", "doc/file load error: " + e.getMessage(), e);
            return;
        }
        RagDocument doc = loaded.doc();
        RagDocumentFile file = loaded.file();

        final String apiKey;
        try {
            apiKey = apiKeyResolver.resolve(doc);
        } catch (Exception e) {
            ragJobStateMachine.onGraphSkip(job, "GRAPH_UAK_MISSING",
                    "no api key for graph extraction: " + e.getMessage(), e);
            return;
        }

        RagProperties.Graph graph = props.graph();
        String aiServiceName = graph.resolveAiServiceName(file.getFileSize());
        String modelPreference = graph.resolveModelPreference(file.getFileSize());
        String modelCode = graph.resolveModelCode(file.getFileSize());

        try {
            long total = chunkRepo.countByDoc(doc.getId());
            if (total <= 0) {
                retryLater(job, "GRAPH_CHUNKS_NOT_READY", "no chunks for graph extraction yet", null);
                return;
            }
            log.info("[{}][{}] handle start. docId={} chunks={} fileSize={} aiService={} preference={} modelCode={} batchSize={}",
                    logPrefix(), traceId, doc.getId(), total, file.getFileSize(),
                    aiServiceName, modelPreference, modelCode, graph.chunkBatchSize());

            // 재실행 idempotency: Pass1 canonical 엔티티는 보존하고 관계·브리지만 제거한 뒤 다시 만든다.
            // (엔티티 삭제는 파이프라인 진입 단계인 Pass1(EXTRACT_ENTITY)이 문서 단위로 담당한다.)
            relationRepo.deleteByDocumentId(doc.getId());
            entityChunkRepo.deleteByDocumentId(doc.getId());

            // 문서 단위 누적 상태(배치 간 공유). 키는 EntityNameNormalizer 정규화형(표기 흔들림 흡수).
            Map<String, UUID> entityIdByKey = new HashMap<>();   // type norm(name) -> id
            Map<String, UUID> entityIdByName = new HashMap<>();  // norm(name) -> id (관계 해소용)
            Set<String> bridgeSeen = new HashSet<>();            // entityId chunkId
            Set<String> relationSeen = new HashSet<>();          // srcId dstId relation

            // Pass1 이 확정한 기존 엔티티를 정규화 키로 선적재 → Pass2 는 raw 이름 조회 없이 재사용해
            // 띄어쓰기 변형으로 인한 중복 엔티티 생성을 막는다(엔티티는 Pass1 이 소유).
            for (RagEntity e : entityRepo.findByDocumentId(doc.getId())) {
                String norm = EntityNameNormalizer.normalize(e.getName());
                entityIdByKey.putIfAbsent(e.getType() + " " + norm, e.getEntityId());
                entityIdByName.putIfAbsent(norm, e.getEntityId());
            }

            int fromIndex = 0;
            int batchSize = graph.chunkBatchSize();
            int entityCount = 0;
            int relationCount = 0;

            while (true) {
                List<RagChunk> batch = chunkRepo.findPageByDoc(
                        doc.getId(), fromIndex, PageRequest.of(0, batchSize));
                if (batch == null || batch.isEmpty()) {
                    break;
                }
                fromIndex = batch.get(batch.size() - 1).getChunkIndex() + 1;

                List<String> texts = new ArrayList<>(batch.size());
                for (RagChunk c : batch) {
                    String text = readChunkText(doc, file, c, traceId);
                    texts.add(text == null ? "" : text);
                }
                if (texts.stream().allMatch(String::isBlank)) {
                    continue;
                }

                String prompt = GraphPromptTexts.buildExtractionPrompt(
                        texts,
                        graph.maxCharsPerChunk(),
                        vocabService.getEntityTypes(),
                        vocabService.getRelationTypes(),
                        vocabService.vocabulary().relationTypeDefault());
                String raw = extractionClient.complete(
                        aiServiceName, modelPreference, modelCode, prompt, apiKey, job.getTransactionId());

                JsonNode root = parseJson(raw);
                if (root == null) {
                    log.warn("[{}][{}] LLM 응답 JSON 파싱 불가 — 배치 건너뜀. chunkIndexFrom={}",
                            logPrefix(), traceId, batch.get(0).getChunkIndex());
                    continue;
                }

                entityCount += persistEntities(
                        doc.getId(), batch, root.path("entities"),
                        entityIdByKey, entityIdByName, bridgeSeen);
                relationCount += persistRelations(
                        doc.getId(), root.path("relations"), entityIdByName, relationSeen);
            }

            log.info("[{}][{}] graph extraction done. entities={} relations={}",
                    logPrefix(), traceId, entityCount, relationCount);
            success(job, null);

        } catch (Exception e) {
            if (isTransient(e)) {
                ragJobStateMachine.onGraphTransientRequeue(job, "GRAPH_TRANSIENT",
                        "graph transient failure: " + e.getMessage(), e);
            } else {
                ragJobStateMachine.onGraphSkip(job, "GRAPH_EXCEPTION",
                        "graph extraction failed: " + e.getMessage(), e);
            }
        }
    }

    /** entities 노드를 dedup upsert 하고, 등장 청크로 브리지를 연결한다. 신규 저장된 엔티티 수 반환. */
    private int persistEntities(UUID documentId,
                                List<RagChunk> batch,
                                JsonNode entities,
                                Map<String, UUID> entityIdByKey,
                                Map<String, UUID> entityIdByName,
                                Set<String> bridgeSeen) {
        if (entities == null || !entities.isArray()) {
            return 0;
        }
        int created = 0;
        for (JsonNode en : entities) {
            String name = text(en, "name");
            if (name == null || name.isBlank()) {
                continue;
            }
            name = name.trim();
            String type = normalizeType(text(en, "type"));
            String norm = EntityNameNormalizer.normalize(name);
            String key = type + " " + norm;

            // 맵은 handle() 에서 Pass1 기존 엔티티로 선적재됨 → 여기 없으면 Pass1 이 놓친 신규 엔티티.
            UUID entityId = entityIdByKey.get(key);
            if (entityId == null) {
                entityId = IdGenerator.newId();
                entityRepo.save(new RagEntity(entityId, documentId, name, type));
                created++;
                entityIdByKey.put(key, entityId);
                entityIdByName.putIfAbsent(norm, entityId);
            }

            linkBridges(entityId, batch, en.path("chunks"), bridgeSeen);
        }
        return created;
    }

    /** 엔티티가 등장한 배치-로컬 청크 인덱스로 브리지(rag_entity_chunk)를 생성한다. */
    private void linkBridges(UUID entityId, List<RagChunk> batch, JsonNode chunks, Set<String> bridgeSeen) {
        if (chunks == null || !chunks.isArray()) {
            return;
        }
        for (JsonNode idx : chunks) {
            if (!idx.canConvertToInt()) {
                continue;
            }
            int local = idx.asInt();
            if (local < 0 || local >= batch.size()) {
                continue;
            }
            UUID chunkId = batch.get(local).getId();
            String seenKey = entityId + " " + chunkId;
            if (bridgeSeen.add(seenKey)) {
                entityChunkRepo.save(new RagEntityChunk(IdGenerator.newId(), entityId, chunkId));
            }
        }
    }

    /** relations 노드를 이름 해소해 저장한다. 저장된 관계 수 반환. */
    private int persistRelations(UUID documentId,
                                 JsonNode relations,
                                 Map<String, UUID> entityIdByName,
                                 Set<String> relationSeen) {
        if (relations == null || !relations.isArray()) {
            return 0;
        }
        int created = 0;
        for (JsonNode rel : relations) {
            String src = text(rel, "src");
            String dst = text(rel, "dst");
            if (src == null || dst == null || src.isBlank() || dst.isBlank()) {
                continue;
            }
            UUID srcId = entityIdByName.get(EntityNameNormalizer.normalize(src));
            UUID dstId = entityIdByName.get(EntityNameNormalizer.normalize(dst));
            if (srcId == null || dstId == null || srcId.equals(dstId)) {
                continue;
            }
            String relation = normalizeRelation(text(rel, "relation"));
            String seenKey = srcId + " " + dstId + " " + relation;
            if (!relationSeen.add(seenKey)) {
                continue;
            }
            String label = text(rel, "label");
            Double confidence = clampConfidence(rel.path("confidence"));
            relationRepo.save(new RagEntityRelation(
                    IdGenerator.newId(), documentId, srcId, dstId, relation, label, confidence));
            created++;
        }
        return created;
    }

    /** LLM 원문에서 코드펜스를 제거하고 첫 JSON 오브젝트를 파싱한다. 실패 시 null. */
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

    private byte[] readChunkBytesOrNull(RagDocument doc, RagDocumentFile file, RagChunk chunk, String traceId) {
        String canonical = documentLoader.canonicalChunkStorageKey(file, doc.getId(), chunk.getChunkIndex());
        byte[] bytes = storageClient.downloadBytesIfPresent(canonical, traceId);
        if (bytes != null && bytes.length > 0) {
            return bytes;
        }
        String dbKey = chunk.getStorageKey();
        if (dbKey != null && !dbKey.isBlank() && !dbKey.equals(canonical)) {
            return storageClient.downloadBytesIfPresent(dbKey, traceId);
        }
        return bytes;
    }

    private String readChunkText(RagDocument doc, RagDocumentFile file, RagChunk chunk, String traceId) {
        byte[] bytes = readChunkBytesOrNull(doc, file, chunk, traceId);
        return (bytes == null || bytes.length == 0) ? null : new String(bytes, StandardCharsets.UTF_8);
    }

    private static String text(JsonNode node, String field) {
        JsonNode v = node.get(field);
        return (v == null || v.isNull()) ? null : v.asText();
    }

    private String normalizeType(String type) {
        return vocabService.normalizeEntityType(type);
    }

    private String normalizeRelation(String relation) {
        return vocabService.normalizeRelationType(relation);
    }

    private static Double clampConfidence(JsonNode node) {
        if (node == null || node.isNull() || !node.isNumber()) {
            return null;
        }
        double v = node.asDouble();
        if (v < 0) {
            return 0.0;
        }
        if (v > 1) {
            return 1.0;
        }
        return v;
    }

    private static boolean isTransient(Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            String n = t.getClass().getSimpleName();
            if (n.contains("Timeout") || n.contains("WebClientRequest") || n.contains("Connect")) {
                return true;
            }
            if (t instanceof com.init.worker.error.exception.UpstreamErrorException up) {
                int status = up.getStatus();
                if (status == 429 || status == 408 || status >= 500) {
                    return true;
                }
            }
        }
        return false;
    }
}
