package com.ragservice.worker.service;

import com.ragservice.worker.config.RagProperties;
import com.ragservice.worker.domain.RagChunk;
import com.ragservice.worker.dto.CitationDto;
import com.ragservice.worker.dto.EmbeddingInput;
import com.ragservice.worker.dto.RetrieveRequest;
import com.ragservice.worker.dto.RetrieveResponse;
import com.ragservice.worker.error.code.ErrorCodes;
import com.ragservice.worker.error.exception.AppException;
import com.ragservice.worker.qdrant.QdrantClient;
import com.ragservice.worker.qdrant.QdrantModels;
import com.ragservice.worker.rag.PersonalCategoryIds;
import com.ragservice.worker.repo.RagChunkRepository;
import com.ragservice.worker.storage.StorageClient;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

@Slf4j
@Service
@ConditionalOnProperty(name = "rag.app.role", havingValue = "api")
public class RagRetrieveService {

    private static final double MIN_SCORE = 0.25;
    private static final double MIN_PRINTABLE_RATIO = 0.70;

    /** 그래프 traverse+융합에 쓸 수 있는 최대 시간(ms). 초과/예외 시 벡터-only 폴백. */
    private static final long GRAPH_BUDGET_MS = 800L;
    /**
     * RRF 공식 {@code 1/(k+rank)} 의 k.
     * 클수록 상위·하위 점수 차이가 완만해져, 한 리스트의 1등이 전체를 독식하기 어렵다(관례값 60).
     */
    private static final int RRF_K = 60;
    /** 벡터 topK 밖에 추가로 끼워 넣을 수 있는 "그래프만 찾은" 청크 상한. */
    private static final int GRAPH_EXTRA_CITATIONS = 3;

    private final AiEmbeddingClient aiEmbeddingClient;
    private final QdrantClient qdrantClient;
    private final RagCategoryService categoryService;
    private final RagCitationEnricher citationEnricher;
    private final RagProperties ragProperties;
    private final RagGraphTraversalService graphTraversalService;
    private final RagChunkRepository ragChunkRepository;
    private final StorageClient storageClient;

    @Value("${rag.embedding.ai-service-name:openai}")
    private String embeddingAiServiceName;

    public RagRetrieveService(
            AiEmbeddingClient aiEmbeddingClient,
            QdrantClient qdrantClient,
            RagCategoryService categoryService,
            RagCitationEnricher citationEnricher,
            RagProperties ragProperties,
            RagGraphTraversalService graphTraversalService,
            RagChunkRepository ragChunkRepository,
            StorageClient storageClient) {
        this.aiEmbeddingClient = aiEmbeddingClient;
        this.qdrantClient = qdrantClient;
        this.categoryService = categoryService;
        this.citationEnricher = citationEnricher;
        this.ragProperties = ragProperties;
        this.graphTraversalService = graphTraversalService;
        this.ragChunkRepository = ragChunkRepository;
        this.storageClient = storageClient;
    }

    @Transactional(readOnly = true)
    public RetrieveResponse retrieve(
            String aiServiceName,
            RetrieveRequest req,
            String userApiKey,
            UUID userNo,
            UUID transactionId) {
        String searchQuery = req.searchQuery() == null ? "" : req.searchQuery().trim();
        if (searchQuery.isBlank()) {
            throw new AppException(ErrorCodes.Api.BAD_REQUEST_SEARCH_QUERY_REQUIRED);
        }

        if (req.categoryId() != null
                && !PersonalCategoryIds.isPersonal(req.categoryId(), configuredPersonalCategoryId())) {
            categoryService.requireUsableCategory(req.categoryId());
        }

        int topK = resolveTopK();
        String searchCategoryId = PersonalCategoryIds.searchCategoryIdOrNull(
                req.categoryId(), configuredPersonalCategoryId());

        UUID embedTxId = UUID.randomUUID();
        log.info(
                "[RAG][{}][RETRIEVE] embed start embedTx={} aiService={} topK={} queryLen={}",
                transactionId,
                embedTxId,
                aiServiceName,
                topK,
                searchQuery.length());

        List<float[]> vectors = aiEmbeddingClient.requestDocumentEmbeddings(
                resolveEmbeddingAiServiceName(aiServiceName),
                embedTxId,
                List.of(new EmbeddingInput("query", searchQuery)),
                userApiKey);

        if (vectors.isEmpty() || vectors.get(0).length == 0) {
            throw new AppException(ErrorCodes.Api.QUERY_EMBEDDING_EMPTY);
        }
        validateEmbeddingDimension(vectors.get(0));

        QdrantModels.SearchResponse searchResponse = qdrantClient.search(
                vectors.get(0),
                topK,
                userNo,
                searchCategoryId,
                req.documentId());

        List<CitationDto> raw = mapSearchResults(searchResponse, transactionId);
        GraphAugmentResult augment = augmentWithGraph(raw, searchQuery, topK, transactionId, req.graphEnabled());
        List<CitationDto> enriched = citationEnricher.enrich(augment.citations());

        log.info(
                "[RAG][{}][RETRIEVE] done embedTx={} rawHits={} vectorCitations={} citations={} graphApplied={}",
                transactionId,
                embedTxId,
                searchResponse == null || searchResponse.result() == null ? 0 : searchResponse.result().size(),
                raw.size(),
                enriched.size(),
                augment.applied());

        return new RetrieveResponse(
                enriched,
                augment.applied(),
                augment.graphChunkCount(),
                augment.graphOnlyPromotedCount());
    }

    /**
     * 벡터 citation 위에 그래프 탐색 결과를 얹는다(add-on).
     *
     * <p>실패·타임아웃·disabled 시 원본 벡터 리스트를 그대로 반환한다.
     * 그래프 탐색 범위는 벡터가 이미 고른 문서 id 안으로만 한정(인가 스코프).
     */
    private GraphAugmentResult augmentWithGraph(
            List<CitationDto> vectorCitations,
            String query,
            int topK,
            UUID transactionId,
            Boolean graphEnabledOverride) {
        boolean effectiveEnabled = resolveGraphEnabled(graphEnabledOverride);
        if (!effectiveEnabled || vectorCitations.isEmpty()) {
            return GraphAugmentResult.unchanged(vectorCitations);
        }

        // 벡터 hit 문서만 인가 스코프로 사용 → 교차 테넌트/타 문서 그래프 누수 방지
        List<UUID> authorizedDocIds = vectorCitations.stream()
                .map(CitationDto::documentId)
                .filter(id -> id != null && !id.isBlank())
                .map(id -> { try { return UUID.fromString(id); } catch (Exception e) { return null; } })
                .filter(Objects::nonNull)
                .distinct()
                .toList();
        if (authorizedDocIds.isEmpty()) {
            return GraphAugmentResult.unchanged(vectorCitations);
        }

        try {
            // 예산 안에서만 traverse. 초과 시 TimeoutException → catch 에서 벡터-only
            long t0 = System.nanoTime();
            List<UUID> graphChunkIds = CompletableFuture
                    .supplyAsync(() -> graphTraversalService.traverse(query, authorizedDocIds))
                    .get(GRAPH_BUDGET_MS, TimeUnit.MILLISECONDS);
            long traverseMs = (System.nanoTime() - t0) / 1_000_000;
            log.info("[RAG][{}][GRAPH] traverseMs={} budgetMs={}", transactionId, traverseMs, GRAPH_BUDGET_MS);
            if (graphChunkIds == null || graphChunkIds.isEmpty()) {
                return GraphAugmentResult.unchanged(vectorCitations);
            }
            return fuseByRrf(vectorCitations, graphChunkIds, topK, transactionId);
        } catch (Exception e) {
            log.warn("[RAG][{}][GRAPH] traverse skipped (fallback to vector-only). reason={}",
                    transactionId, e.toString());
            return GraphAugmentResult.unchanged(vectorCitations);
        }
    }

    private boolean resolveGraphEnabled(Boolean override) {
        boolean configEnabled = ragProperties.graph() != null && ragProperties.graph().enabled();
        if (override == null) {
            return configEnabled;
        }
        // true여도 서버 설정이 꺼져 있으면 그래프를 쓰지 않는다(인덱싱 없거나 의도적 비활성).
        return override && configEnabled;
    }

    private record GraphAugmentResult(
            List<CitationDto> citations,
            boolean applied,
            int graphChunkCount,
            int graphOnlyPromotedCount
    ) {
        static GraphAugmentResult unchanged(List<CitationDto> citations) {
            return new GraphAugmentResult(citations, false, 0, 0);
        }
    }

    /**
     * 벡터·그래프 두 순위 리스트를 RRF로 하나로 합친다.
     *
     * <pre>
     * score(chunk) = Σ 1/(RRF_K + rank)   // rank는 0-based → 코드는 (RRF_K + i + 1)
     *
     * - 벡터에만 있음     → 벡터 순위 점수만
     * - 그래프에만 있음   → 그래프 순위 점수 + MinIO 텍스트로 citation 생성(최대 3)
     * - 양쪽에 있음       → 점수 합산(순위 상승 유리)
     * </pre>
     *
     * 벡터 유사도 점수와 그래프 weight 단위가 달라서, 원점수 대신 "순위"만으로 공정하게 섞는다.
     */
    private GraphAugmentResult fuseByRrf(
            List<CitationDto> vectorCitations, List<UUID> graphChunkIds, int topK, UUID transactionId) {
        // 최종 citation 객체 재사용용 (텍스트·메타는 벡터 쪽 것을 우선)
        Map<String, CitationDto> vectorByChunk = new LinkedHashMap<>();
        for (CitationDto c : vectorCitations) {
            if (c.chunkId() != null && !c.chunkId().isBlank()) {
                vectorByChunk.put(c.chunkId(), c);
            }
        }

        // --- RRF 점수 누적 ---
        Map<String, Double> rrf = new HashMap<>();
        // 벡터 리스트 순위 → 점수. i=0(1등)이 가장 큼
        for (int i = 0; i < vectorCitations.size(); i++) {
            String chunkId = vectorCitations.get(i).chunkId();
            if (chunkId != null && !chunkId.isBlank()) {
                rrf.merge(chunkId, 1.0 / (RRF_K + i + 1), Double::sum);
            }
        }
        // 그래프 리스트 순위(traverse 가 weight 순으로 준 결과) → 같은 공식. 중복 chunk 는 합산
        List<String> graphIdStrings = graphChunkIds.stream().map(UUID::toString).toList();
        for (int i = 0; i < graphIdStrings.size(); i++) {
            rrf.merge(graphIdStrings.get(i), 1.0 / (RRF_K + i + 1), Double::sum);
        }

        // 벡터에 없던 그래프 청크만 본문 로드(citation 승격). 실패해도 융합은 계속
        Map<String, CitationDto> graphOnly = loadGraphOnlyCitations(
                graphChunkIds, vectorByChunk.keySet(), transactionId);

        // 점수 내림차순 → citation 매핑 → topK(+그래프 여유 3) 절단
        List<CitationDto> ranked = rrf.entrySet().stream()
                .sorted(Map.Entry.<String, Double>comparingByValue(Comparator.reverseOrder()))
                .map(e -> vectorByChunk.containsKey(e.getKey())
                        ? vectorByChunk.get(e.getKey())
                        : graphOnly.get(e.getKey()))
                .filter(Objects::nonNull) // 그래프 전용인데 텍스트 로드 실패면 제외
                .limit((long) topK + GRAPH_EXTRA_CITATIONS)
                .toList();

        log.info("[RAG][{}][GRAPH] fused vector={} graph={} graphOnlyPromoted={} final={}",
                transactionId, vectorCitations.size(), graphChunkIds.size(), graphOnly.size(), ranked.size());
        return new GraphAugmentResult(ranked, true, graphChunkIds.size(), graphOnly.size());
    }

    /**
     * 벡터 hit에 없는 그래프 청크만 MinIO에서 읽어 citation으로 만든다.
     *
     * <p>그래프 순위 앞쪽부터 최대 {@value #GRAPH_EXTRA_CITATIONS}개.
     * 개별 로드 실패는 스킵(비차단) — 전체 retrieve를 막지 않는다.
     */
    private Map<String, CitationDto> loadGraphOnlyCitations(
            List<UUID> graphChunkIds, java.util.Set<String> vectorChunkIds, UUID transactionId) {
        // 이미 벡터 citation에 있는 건 본문이 있으므로 제외
        List<UUID> targets = graphChunkIds.stream()
                .filter(id -> !vectorChunkIds.contains(id.toString()))
                .limit(GRAPH_EXTRA_CITATIONS)
                .toList();
        if (targets.isEmpty()) {
            return Map.of();
        }

        Map<UUID, RagChunk> chunkById = new HashMap<>();
        ragChunkRepository.findAllById(targets).forEach(c -> chunkById.put(c.getId(), c));

        Map<String, CitationDto> out = new LinkedHashMap<>();
        for (UUID chunkId : targets) {
            RagChunk chunk = chunkById.get(chunkId);
            if (chunk == null || chunk.getStorageKey() == null || chunk.getStorageKey().isBlank()) {
                continue;
            }
            try {
                byte[] bytes = storageClient.downloadBytesIfPresent(chunk.getStorageKey(), transactionId.toString());
                if (bytes == null || bytes.length == 0) {
                    continue;
                }
                String text = new String(bytes, StandardCharsets.UTF_8).trim();
                if (!isMeaningfulText(text)) {
                    continue;
                }
                // location/sourceUri 는 뒤이은 citationEnricher 가 RagChunk 기준으로 채운다.
                out.put(chunkId.toString(), new CitationDto(
                        chunkId.toString(),
                        chunk.getDocumentId().toString(),
                        null, null, null, null, null, text));
            } catch (Exception e) {
                log.warn("[RAG][{}][GRAPH] graph-only chunk text load failed (skip). chunkId={}, err={}",
                        transactionId, chunkId, e.toString());
            }
        }
        return out;
    }

    private String resolveEmbeddingAiServiceName(String pathAiServiceName) {
        if (embeddingAiServiceName != null && !embeddingAiServiceName.isBlank()) {
            return embeddingAiServiceName.trim();
        }
        if (pathAiServiceName != null && !pathAiServiceName.isBlank()) {
            return pathAiServiceName.trim();
        }
        return "openai";
    }

    private int resolveTopK() {
        if (ragProperties.qdrant() != null && ragProperties.qdrant().topKDefault() > 0) {
            return ragProperties.qdrant().topKDefault();
        }
        return 5;
    }

    private void validateEmbeddingDimension(float[] vector) {
        Integer expected = ragProperties.embedding() != null ? ragProperties.embedding().dimension() : null;
        if (expected == null || expected <= 0) {
            return;
        }
        if (vector.length != expected) {
            throw new AppException(
                    ErrorCodes.Api.QUERY_EMBEDDING_DIMENSION_MISMATCH,
                    Map.of("expected", expected, "actual", vector.length));
        }
    }

    private List<CitationDto> mapSearchResults(QdrantModels.SearchResponse response, UUID transactionId) {
        if (response == null || response.result() == null || response.result().isEmpty()) {
            return List.of();
        }

        List<CitationDto> out = new ArrayList<>();
        for (QdrantModels.SearchResponse.Point point : response.result()) {
            if (point.score() < MIN_SCORE) {
                continue;
            }

            Map<String, Object> payload = point.payload() != null ? point.payload() : Map.of();
            String text = stringValue(payload.get("page_content")).trim();
            if (!isMeaningfulText(text)) {
                continue;
            }

            String chunkId = resolveChunkId(point);
            String documentId = stringValue(payload.get("document_id"));
            LocationParts location = parseLocation(stringValue(payload.get("location")));

            out.add(new CitationDto(
                    chunkId,
                    documentId,
                    BigDecimal.valueOf(point.score()).setScale(4, RoundingMode.HALF_UP),
                    location.page(),
                    location.slide(),
                    location.sheet(),
                    null,
                    text));
        }
        return out;
    }

    private static String resolveChunkId(QdrantModels.SearchResponse.Point point) {
        if (point.id() != null) {
            String pointId = String.valueOf(point.id()).trim();
            if (!pointId.isBlank()) {
                return pointId;
            }
        }
        Map<String, Object> payload = point.payload();
        if (payload == null) {
            return null;
        }
        String chunkId = stringValue(payload.get("chunk_id")).trim();
        return chunkId.isBlank() ? null : chunkId;
    }

    private static boolean isMeaningfulText(String text) {
        if (text.length() < 10) {
            return false;
        }
        long printable = text.chars()
                .filter(c -> c == '\n' || c == '\t' || c == '\r' || (!Character.isISOControl(c) && c != 127))
                .count();
        return (double) printable / text.length() >= MIN_PRINTABLE_RATIO;
    }

    private static String stringValue(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private static LocationParts parseLocation(String location) {
        if (location == null || location.isBlank()) {
            return new LocationParts(null, null, null);
        }
        if (location.startsWith("p.")) {
            try {
                return new LocationParts(Integer.parseInt(location.substring(2).trim()), null, null);
            } catch (NumberFormatException ignored) {
                return new LocationParts(null, null, null);
            }
        }
        if (location.startsWith("slide.")) {
            try {
                return new LocationParts(null, Integer.parseInt(location.substring(6).trim()), null);
            } catch (NumberFormatException ignored) {
                return new LocationParts(null, null, null);
            }
        }
        return new LocationParts(null, null, location);
    }

    private String configuredPersonalCategoryId() {
        if (ragProperties.categories() == null
                || ragProperties.categories().personalCategoryId() == null
                || ragProperties.categories().personalCategoryId().isBlank()) {
            return null;
        }
        return ragProperties.categories().personalCategoryId().trim();
    }

    private record LocationParts(Integer page, Integer slide, String sheet) {}
}
