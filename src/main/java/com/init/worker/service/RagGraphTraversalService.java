package com.init.worker.service;

import com.init.worker.domain.RagEntity;
import com.init.worker.domain.RagEntityChunk;
import com.init.worker.domain.RagEntityRelation;
import com.init.worker.repo.RagEntityChunkRepository;
import com.init.worker.repo.RagEntityRelationRepository;
import com.init.worker.repo.RagEntityRepository;
import com.init.worker.util.EntityNameNormalizer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * QA/retrieve 검색 경로에서 쓰는 Graph RAG 탐색기 (API 역할 전용).
 *
 * <p>벡터 검색 citation을 받은 뒤, 같은 인가 문서 범위 안에서 그래프로 관련 청크를 더 찾는다.
 * 결과는 {@link RagRetrieveService}가 RRF로 벡터 결과와 합친다.
 *
 * <pre>
 * 흐름 요약
 *   질문 텍스트
 *     → (1) 문서 내 엔티티 이름 부분매칭 = seed
 *     → (2) seed와 관계(엣지)로 연결된 이웃 = 1-hop
 *     → (3) rag_entity_chunk 브리지로 청크 id 환원 + 가중 합산
 *     → 점수 상위 MAX_GRAPH_CHUNKS 개 반환
 * </pre>
 *
 * <p><b>스코프 안전:</b> 엔티티/관계는 벡터 검색이 이미 반환한 문서 id 안에서만 본다.
 * 교차 테넌트 유출을 막고, 검색 경로에서는 Redis {@code entity:link} 를 쓰지 않는다
 * (그 캐시는 인덱싱 Pass1 선워밍용).
 */
@Slf4j
@Service
@ConditionalOnProperty(name = "rag.app.role", havingValue = "api")
@RequiredArgsConstructor
public class RagGraphTraversalService {

    /** 질문에 매칭된 seed(그래프 탐색의 시작 엔티티) 엔티티 최대 개수. 너무 크면 DB·관계 조회가 비대해짐. (2026-07-23 확정) */
    private static final int MAX_SEEDS = 20;

    /** 최종으로 넘길 그래프 청크 후보 상한. RRF 입력 폭을 제한한다. (2026-07-23 확정: 30→50) */
    private static final int MAX_GRAPH_CHUNKS = 50;

    /** 질문에 직접 매칭된 seed 엔티티→청크 가중 (이웃보다 높게). */
    private static final double SEED_WEIGHT = 2.0;

    /** seed와 1-hop으로만 연결된 이웃 엔티티→청크 가중. */
    private static final double HOP_WEIGHT = 1.0;

    private final RagEntityRepository entityRepo;
    private final RagEntityRelationRepository relationRepo;
    private final RagEntityChunkRepository entityChunkRepo;

    /**
     * 인가 문서 안에서 질문과 관련된 청크 id를 그래프 탐색으로 모은다.
     *
     * @param query            사용자 질문 원문
     * @param authorizedDocIds 벡터 검색이 이미 고른 문서 id (이 밖은 보지 않음)
     * @return 관련도(가중 합) 내림차순 청크 id. seed가 없거나 브리지가 없으면 빈 리스트
     */
    @Transactional(readOnly = true)
    public List<UUID> traverse(String query, List<UUID> authorizedDocIds) {
        if (query == null || query.isBlank() || authorizedDocIds == null || authorizedDocIds.isEmpty()) {
            return List.of();
        }
        // 엔티티 이름과 동일한 규칙(EntityNameNormalizer)으로 질문을 정규화해 표기 흔들림을 흡수한다.
        String normalizedQuery = EntityNameNormalizer.normalize(query);

        // --- (1) Seed 링킹 ---
        // 인가 문서의 rag_entity 중, 이름이 질문 문자열에 포함되면 seed.
        // 예) 질문 "환불정책 예외는?" + 엔티티 "환불정책" → seed.
        // 길이 2 미만은 잡음(조사·짧은 토큰)으로 보고 제외.
        Set<UUID> seedEntityIds = new LinkedHashSet<>();
        for (UUID docId : authorizedDocIds) {
            for (RagEntity e : entityRepo.findByDocumentId(docId)) {
                if (seedEntityIds.size() >= MAX_SEEDS) {
                    break;
                }
                String name = EntityNameNormalizer.normalize(e.getName());
                if (name.length() >= 2 && normalizedQuery.contains(name)) {
                    seedEntityIds.add(e.getEntityId());
                }
            }
        }
        // 질문에 걸린 개념이 없으면 그래프 이득 없음 → 벡터-only 로 폴백되도록 빈 결과
        if (seedEntityIds.isEmpty()) {
            return List.of();
        }

        // --- (2) 1-hop 확장 ---
        // rag_entity_relation 을 양방향(src/dst)으로 조회해 seed의 이웃만 모은다.
        // 2-hop 이상은 아직 안 탄다(지연·노이즈 제어).
        List<UUID> seedList = new ArrayList<>(seedEntityIds);
        Set<UUID> neighborIds = new LinkedHashSet<>();
        for (RagEntityRelation r : relationRepo.findBySrcEntityIdInOrDstEntityIdIn(seedList, seedList)) {
            if (!seedEntityIds.contains(r.getSrcEntityId())) {
                neighborIds.add(r.getSrcEntityId());
            }
            if (!seedEntityIds.contains(r.getDstEntityId())) {
                neighborIds.add(r.getDstEntityId());
            }
        }

        // --- (3) 엔티티 → 청크 환원 ---
        // rag_entity_chunk(브리지)로 citation에 쓸 chunk_id 를 얻고 가중을 합산.
        // 같은 청크가 seed·이웃 양쪽에 걸리면 점수가 더해져 순위가 올라간다.
        Map<UUID, Double> chunkScores = new HashMap<>();
        accumulateBridgeScores(chunkScores, seedList, SEED_WEIGHT);
        if (!neighborIds.isEmpty()) {
            accumulateBridgeScores(chunkScores, new ArrayList<>(neighborIds), HOP_WEIGHT);
        }
        if (chunkScores.isEmpty()) {
            // 엔티티는 있는데 Pass2 브리지가 비어 있는 경우(인덱싱 미완·스킵 등)
            return List.of();
        }

        // 점수 높은 순으로 자르고 RRF 후보로 반환
        List<UUID> ranked = chunkScores.entrySet().stream()
                .sorted(Map.Entry.<UUID, Double>comparingByValue(Comparator.reverseOrder()))
                .limit(MAX_GRAPH_CHUNKS)
                .map(Map.Entry::getKey)
                .toList();

        log.info("[RAG_GRAPH_TRAVERSE] seeds={} neighbors={} chunks={}",
                seedEntityIds.size(), neighborIds.size(), ranked.size());
        return ranked;
    }

    /**
     * 엔티티 id 목록에 연결된 브리지 행을 읽어 chunkScores 에 weight 를 누적한다.
     * soft-delete 된 브리지는 건너뛴다.
     */
    private void accumulateBridgeScores(Map<UUID, Double> chunkScores, List<UUID> entityIds, double weight) {
        if (entityIds.isEmpty()) {
            return;
        }
        for (RagEntityChunk bridge : entityChunkRepo.findByEntityIdIn(entityIds)) {
            if (bridge.isDeleted()) {
                continue;
            }
            chunkScores.merge(bridge.getChunkId(), weight, Double::sum);
        }
    }
}
