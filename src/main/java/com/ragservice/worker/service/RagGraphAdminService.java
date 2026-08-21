package com.ragservice.worker.service;

import com.ragservice.worker.config.RagProperties;
import com.ragservice.worker.domain.RagEntity;
import com.ragservice.worker.domain.RagEntityChunk;
import com.ragservice.worker.domain.RagEntityRelation;
import com.ragservice.worker.dto.AskRequest;
import com.ragservice.worker.dto.CitationDto;
import com.ragservice.worker.dto.QaAskResponse;
import com.ragservice.worker.dto.admin.GraphCategoryCoverageResponse;
import com.ragservice.worker.dto.admin.GraphDocumentViewResponse;
import com.ragservice.worker.dto.admin.GraphQaCompareRequest;
import com.ragservice.worker.dto.admin.GraphQaCompareResponse;
import com.ragservice.worker.dto.admin.GraphTraverseDebugRequest;
import com.ragservice.worker.dto.admin.GraphTraverseDebugResponse;
import com.ragservice.worker.error.code.ErrorCodes;
import com.ragservice.worker.error.exception.AppException;
import com.ragservice.worker.repo.RagDocumentFileRepository;
import com.ragservice.worker.repo.RagDocumentRepository;
import com.ragservice.worker.repo.RagEntityChunkRepository;
import com.ragservice.worker.repo.RagEntityRelationRepository;
import com.ragservice.worker.repo.RagEntityRepository;
import com.ragservice.worker.util.EntityNameNormalizer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * 어드민 임시 테스트용 Graph RAG 조회/QA 비교 서비스 (API 역할).
 */
@Slf4j
@Service
@ConditionalOnProperty(name = "rag.app.role", havingValue = "api")
@RequiredArgsConstructor
public class RagGraphAdminService {

    /** 검색 경로 {@link RagGraphTraversalService} 와 동일 — 2026-07-23 확정 */
    private static final int MAX_SEEDS = 20;
    private static final int MAX_GRAPH_CHUNKS = 50;
    private static final double SEED_WEIGHT = 2.0;
    private static final double HOP_WEIGHT = 1.0;

    private final RagProperties ragProperties;
    private final RagQaService ragQaService;
    private final RagDocumentRepository documentRepository;
    private final RagDocumentFileRepository documentFileRepository;
    private final RagEntityRepository entityRepo;
    private final RagEntityRelationRepository relationRepo;
    private final RagEntityChunkRepository entityChunkRepo;

    @Transactional(readOnly = true)
    public GraphDocumentViewResponse viewDocumentGraph(UUID documentId) {
        requireDocument(documentId);

        List<RagEntity> entities = entityRepo.findByDocumentId(documentId).stream()
                .filter(e -> !e.isDeleted())
                .sorted(Comparator.comparing(RagEntity::getName, String.CASE_INSENSITIVE_ORDER))
                .toList();

        Map<UUID, RagEntity> entityById = entities.stream()
                .collect(Collectors.toMap(RagEntity::getEntityId, e -> e, (a, b) -> a));

        List<RagEntityRelation> relations = relationRepo.findByDocumentId(documentId).stream()
                .filter(r -> !r.isDeleted())
                .sorted(Comparator.comparing(RagEntityRelation::getRelation)
                        .thenComparing(RagEntityRelation::getSrcEntityId))
                .toList();

        List<UUID> entityIds = entities.stream().map(RagEntity::getEntityId).toList();
        List<RagEntityChunk> bridges = entityIds.isEmpty()
                ? List.of()
                : entityChunkRepo.findByEntityIdIn(entityIds).stream()
                        .filter(b -> !b.isDeleted())
                        .sorted(Comparator.comparing(RagEntityChunk::getEntityId)
                                .thenComparing(RagEntityChunk::getChunkId))
                        .toList();

        List<GraphDocumentViewResponse.EntityItem> entityItems = entities.stream()
                .map(e -> new GraphDocumentViewResponse.EntityItem(
                        e.getEntityId().toString(),
                        e.getName(),
                        e.getType(),
                        e.getCreatedAt()))
                .toList();

        List<GraphDocumentViewResponse.RelationItem> relationItems = relations.stream()
                .map(r -> {
                    RagEntity src = entityById.get(r.getSrcEntityId());
                    RagEntity dst = entityById.get(r.getDstEntityId());
                    return new GraphDocumentViewResponse.RelationItem(
                            r.getRelationId().toString(),
                            r.getSrcEntityId().toString(),
                            src != null ? src.getName() : null,
                            r.getDstEntityId().toString(),
                            dst != null ? dst.getName() : null,
                            r.getRelation(),
                            r.getRelationLabel(),
                            r.getConfidence(),
                            r.getCreatedAt());
                })
                .toList();

        List<GraphDocumentViewResponse.BridgeItem> bridgeItems = bridges.stream()
                .map(b -> {
                    RagEntity entity = entityById.get(b.getEntityId());
                    return new GraphDocumentViewResponse.BridgeItem(
                            b.getId().toString(),
                            b.getEntityId().toString(),
                            entity != null ? entity.getName() : null,
                            b.getChunkId().toString(),
                            b.getCreatedAt());
                })
                .toList();

        return new GraphDocumentViewResponse(
                documentId.toString(),
                isGraphEnabled(),
                entityItems.size(),
                relationItems.size(),
                bridgeItems.size(),
                entityItems,
                relationItems,
                bridgeItems);
    }

    @Transactional(readOnly = true)
    public GraphTraverseDebugResponse traverseDebug(GraphTraverseDebugRequest request) {
        requireDocument(request.documentId());

        String query = request.query() == null ? "" : request.query().trim();
        if (query.isBlank()) {
            throw new AppException(ErrorCodes.Api.BAD_REQUEST_SEARCH_QUERY_REQUIRED);
        }

        String normalizedQuery = EntityNameNormalizer.normalize(query);
        boolean enabled = isGraphEnabled();

        if (!enabled) {
            return new GraphTraverseDebugResponse(
                    false,
                    query,
                    normalizedQuery,
                    request.documentId().toString(),
                    List.of(),
                    List.of(),
                    List.of(),
                    List.of());
        }

        List<RagEntity> docEntities = entityRepo.findByDocumentId(request.documentId()).stream()
                .filter(e -> !e.isDeleted())
                .toList();
        Map<UUID, RagEntity> entityById = docEntities.stream()
                .collect(Collectors.toMap(RagEntity::getEntityId, e -> e, (a, b) -> a));

        Set<UUID> seedEntityIds = new LinkedHashSet<>();
        for (RagEntity e : docEntities) {
            if (seedEntityIds.size() >= MAX_SEEDS) {
                break;
            }
            String name = EntityNameNormalizer.normalize(e.getName());
            if (name.length() >= 2 && normalizedQuery.contains(name)) {
                seedEntityIds.add(e.getEntityId());
            }
        }

        List<GraphTraverseDebugResponse.EntityHit> seeds = seedEntityIds.stream()
                .map(id -> toHit(entityById.get(id)))
                .filter(h -> h != null)
                .toList();

        if (seedEntityIds.isEmpty()) {
            return new GraphTraverseDebugResponse(
                    true,
                    query,
                    normalizedQuery,
                    request.documentId().toString(),
                    seeds,
                    List.of(),
                    List.of(),
                    List.of());
        }

        List<UUID> seedList = new ArrayList<>(seedEntityIds);
        Set<UUID> neighborIds = new LinkedHashSet<>();
        List<RagEntityRelation> hopRels = relationRepo
                .findBySrcEntityIdInOrDstEntityIdIn(seedList, seedList).stream()
                .filter(r -> !r.isDeleted())
                .toList();

        for (RagEntityRelation r : hopRels) {
            if (!seedEntityIds.contains(r.getSrcEntityId()) && entityById.containsKey(r.getSrcEntityId())) {
                neighborIds.add(r.getSrcEntityId());
            }
            if (!seedEntityIds.contains(r.getDstEntityId()) && entityById.containsKey(r.getDstEntityId())) {
                neighborIds.add(r.getDstEntityId());
            }
        }

        List<GraphTraverseDebugResponse.EntityHit> neighbors = neighborIds.stream()
                .map(id -> toHit(entityById.get(id)))
                .filter(h -> h != null)
                .toList();

        List<GraphTraverseDebugResponse.HopRelation> hopRelations = hopRels.stream()
                .map(r -> {
                    RagEntity src = entityById.get(r.getSrcEntityId());
                    RagEntity dst = entityById.get(r.getDstEntityId());
                    return new GraphTraverseDebugResponse.HopRelation(
                            r.getRelationId().toString(),
                            r.getSrcEntityId().toString(),
                            src != null ? src.getName() : null,
                            r.getDstEntityId().toString(),
                            dst != null ? dst.getName() : null,
                            r.getRelation(),
                            r.getRelationLabel(),
                            r.getConfidence());
                })
                .toList();

        Map<UUID, Double> chunkScores = new HashMap<>();
        Map<UUID, Set<UUID>> chunkEntities = new HashMap<>();
        accumulate(chunkScores, chunkEntities, seedList, SEED_WEIGHT);
        if (!neighborIds.isEmpty()) {
            accumulate(chunkScores, chunkEntities, new ArrayList<>(neighborIds), HOP_WEIGHT);
        }

        List<GraphTraverseDebugResponse.ChunkHit> chunkHits = chunkScores.entrySet().stream()
                .sorted(Map.Entry.<UUID, Double>comparingByValue(Comparator.reverseOrder()))
                .limit(MAX_GRAPH_CHUNKS)
                .map(e -> new GraphTraverseDebugResponse.ChunkHit(
                        e.getKey().toString(),
                        e.getValue(),
                        chunkEntities.getOrDefault(e.getKey(), Set.of()).stream()
                                .map(UUID::toString)
                                .sorted()
                                .toList()))
                .toList();

        log.info("[RAG_GRAPH_ADMIN] traverse-debug doc={} seeds={} neighbors={} chunks={}",
                request.documentId(), seeds.size(), neighbors.size(), chunkHits.size());

        return new GraphTraverseDebugResponse(
                true,
                query,
                normalizedQuery,
                request.documentId().toString(),
                seeds,
                neighbors,
                hopRelations,
                chunkHits);
    }

    private void accumulate(
            Map<UUID, Double> chunkScores,
            Map<UUID, Set<UUID>> chunkEntities,
            List<UUID> entityIds,
            double weight) {
        if (entityIds.isEmpty()) {
            return;
        }
        for (RagEntityChunk bridge : entityChunkRepo.findByEntityIdIn(entityIds)) {
            if (bridge.isDeleted()) {
                continue;
            }
            chunkScores.merge(bridge.getChunkId(), weight, Double::sum);
            chunkEntities
                    .computeIfAbsent(bridge.getChunkId(), id -> new LinkedHashSet<>())
                    .add(bridge.getEntityId());
        }
    }

    private GraphTraverseDebugResponse.EntityHit toHit(RagEntity entity) {
        if (entity == null) {
            return null;
        }
        return new GraphTraverseDebugResponse.EntityHit(
                entity.getEntityId().toString(),
                entity.getName(),
                entity.getType());
    }

    /** 카테고리 문서별 엔티티/관계/브리지 건수 — 골든셋·튜닝 전 그래프 공백 진단 */
    @Transactional(readOnly = true)
    public GraphCategoryCoverageResponse categoryCoverage(UUID categoryId) {
        List<com.ragservice.worker.domain.RagDocument> docs = documentRepository.findByCategoryId(categoryId).stream()
                .filter(d -> !d.isDeleted())
                .sorted(Comparator.comparing(com.ragservice.worker.domain.RagDocument::getCreatedAt).reversed())
                .toList();

        List<GraphCategoryCoverageResponse.DocumentCoverage> rows = new ArrayList<>();
        int ready = 0;
        for (com.ragservice.worker.domain.RagDocument doc : docs) {
            long entities = entityRepo.countByDocumentIdAndIsDeletedFalse(doc.getId());
            long relations = relationRepo.countByDocumentIdAndIsDeletedFalse(doc.getId());
            long bridges = entityChunkRepo.countActiveByDocumentId(doc.getId());
            boolean graphReady = entities > 0 && bridges > 0;
            if (graphReady) {
                ready++;
            }
            String fileName = documentFileRepository.findById(doc.getFileId())
                    .map(com.ragservice.worker.domain.RagDocumentFile::getOriginalFileName)
                    .orElse(null);
            rows.add(new GraphCategoryCoverageResponse.DocumentCoverage(
                    doc.getId().toString(),
                    fileName,
                    entities,
                    relations,
                    bridges,
                    graphReady));
        }
        return new GraphCategoryCoverageResponse(
                categoryId.toString(),
                rows.size(),
                ready,
                rows.size() - ready,
                rows);
    }


    private void requireDocument(UUID documentId) {
        if (!documentRepository.existsById(documentId)) {
            throw new AppException(ErrorCodes.Api.DOCUMENT_NOT_FOUND, Map.of("documentId", documentId));
        }
    }

    private boolean isGraphEnabled() {
        return ragProperties.graph() != null && ragProperties.graph().enabled();
    }

    /**
     * 동일 질문으로 벡터-only QA → Graph RAG QA 를 순차 실행해 비교한다.
     * 각자 임시 session/message id 를 쓴다.
     */
    public GraphQaCompareResponse compareQa(
            GraphQaCompareRequest request,
            String userApiKey,
            UUID userNo,
            UUID transactionId) {
        String content = request.content() == null ? "" : request.content().trim();
        if (content.isBlank()) {
            throw new AppException(ErrorCodes.Api.BAD_REQUEST_SEARCH_QUERY_REQUIRED);
        }
        if (StringUtils.hasText(request.documentId())) {
            UUID docId;
            try {
                docId = UUID.fromString(request.documentId().trim());
            } catch (IllegalArgumentException e) {
                throw new AppException(
                        ErrorCodes.Api.DOCUMENT_NOT_FOUND,
                        Map.of("documentId", request.documentId()));
            }
            requireDocument(docId);
        }

        String aiServiceName = request.aiServiceName().trim();
        boolean configEnabled = isGraphEnabled();

        GraphQaCompareResponse.ModeResult vectorOnly = runMode(
                aiServiceName,
                content,
                request.categoryId(),
                request.documentId(),
                request.modelCode(),
                false,
                userApiKey,
                userNo,
                transactionId);

        GraphQaCompareResponse.ModeResult graphAugmented = runMode(
                aiServiceName,
                content,
                request.categoryId(),
                request.documentId(),
                request.modelCode(),
                true,
                userApiKey,
                userNo,
                transactionId);

        GraphQaCompareResponse.CitationDiff diff = buildCitationDiff(
                vectorOnly.citations(), graphAugmented.citations());

        log.info(
                "[RAG_GRAPH_ADMIN][{}] qa-compare vectorMs={} graphMs={} graphApplied={} onlyV={} onlyG={} shared={}",
                transactionId,
                vectorOnly.latencyMs(),
                graphAugmented.latencyMs(),
                graphAugmented.graphApplied(),
                diff.onlyInVector().size(),
                diff.onlyInGraph().size(),
                diff.shared().size());

        return new GraphQaCompareResponse(
                content,
                configEnabled,
                aiServiceName,
                vectorOnly,
                graphAugmented,
                diff);
    }

    private GraphQaCompareResponse.ModeResult runMode(
            String aiServiceName,
            String content,
            UUID categoryId,
            String documentId,
            String modelCode,
            boolean graphRequested,
            String userApiKey,
            UUID userNo,
            UUID baseTransactionId) {
        UUID sessionId = UUID.randomUUID();
        UUID messageId = UUID.randomUUID();
        // 모드별 추적 분리 (동일 배치 비교라도 로그/업스트림 TX 구분)
        UUID modeTxId = UUID.randomUUID();
        AskRequest ask = new AskRequest(
                sessionId,
                messageId,
                content,
                categoryId,
                blankToNull(documentId),
                blankToNull(modelCode),
                graphRequested);

        long started = System.currentTimeMillis();
        RagQaService.QaRunResult result = ragQaService.askDetailed(
                aiServiceName, ask, userApiKey, userNo, modeTxId);
        long latencyMs = System.currentTimeMillis() - started;

        QaAskResponse res = result.response();
        List<CitationDto> citations = res.citations() != null ? res.citations() : List.of();

        log.info(
                "[RAG_GRAPH_ADMIN][{}] qa-mode graphRequested={} graphApplied={} latencyMs={} baseTx={}",
                modeTxId,
                graphRequested,
                result.graphApplied(),
                latencyMs,
                baseTransactionId);

        return new GraphQaCompareResponse.ModeResult(
                graphRequested,
                result.graphApplied(),
                result.graphChunkCount(),
                result.graphOnlyPromotedCount(),
                latencyMs,
                res.sessionId(),
                res.messageId(),
                res.answer(),
                res.modelName(),
                res.provider(),
                citations);
    }

    private static GraphQaCompareResponse.CitationDiff buildCitationDiff(
            List<CitationDto> vectorCitations,
            List<CitationDto> graphCitations) {
        Set<String> vectorIds = chunkIds(vectorCitations);
        Set<String> graphIds = chunkIds(graphCitations);

        List<String> onlyInVector = vectorIds.stream()
                .filter(id -> !graphIds.contains(id))
                .sorted()
                .toList();
        List<String> onlyInGraph = graphIds.stream()
                .filter(id -> !vectorIds.contains(id))
                .sorted()
                .toList();
        List<String> shared = vectorIds.stream()
                .filter(graphIds::contains)
                .sorted()
                .toList();
        return new GraphQaCompareResponse.CitationDiff(onlyInVector, onlyInGraph, shared);
    }

    private static Set<String> chunkIds(List<CitationDto> citations) {
        if (citations == null || citations.isEmpty()) {
            return Set.of();
        }
        return citations.stream()
                .map(CitationDto::chunkId)
                .filter(Objects::nonNull)
                .filter(id -> !id.isBlank())
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    private static String blankToNull(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }
}
