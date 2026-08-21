package com.ragservice.worker.qdrant;

import com.ragservice.worker.config.RagProperties;
import com.ragservice.worker.rag.PersonalCategoryIds;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import java.time.Duration;
import java.util.*;

/**
 * Qdrant HTTP API 호출을 담당하는 클라이언트.
 *
 * 역할:
 * - 컬렉션 존재 확인 및 없으면 생성(ensureCollection)
 * - 문서 단위로 포인트 삭제(deleteByDocument) // payload의 document_id 기준
 * - 벡터 포인트 업서트(upsert)
 *
 * 특징:
 * - WebClient를 사용해 Qdrant REST API를 호출한다.
 * - 실패 시 WebClientResponseException을 로그로 남기고 그대로 throw 한다(상위에서 처리).
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class QdrantClient {
    private static final long DEFAULT_TIMEOUT_MS = 3000L;

    /** Qdrant 호출에 사용하는 WebClient(spring bean) */
    private final WebClient webClient; // spring bean

    /** rag.qdrant.* 설정값 */
    private final RagProperties props;

    /**
     * 컬렉션이 존재하는지 확인하고 없으면 생성한다.
     *
     * 흐름:
     * 1) GET /collections/{collection} 호출
     * 2) 404(NotFound)이면 PUT /collections/{collection} 로 생성
     *
     * vector 설정:
     * - size: embedding 벡터 차원
     * - distance: Cosine 유사도 방식
     */
    public void ensureCollection(String baseUrl, String collection, int vectorSize) {
        String url = baseUrl + "/collections/" + collection;

        try {
            // 1) 존재 확인
            webClient.get()
                    .uri(url)
                    .retrieve()
                    .bodyToMono(String.class)
                    .block();
        } catch (WebClientResponseException.NotFound nf) {
            // 2) 없으면 생성
            Map<String, Object> body = new HashMap<>();
            Map<String, Object> vectors = new HashMap<>();
            vectors.put("size", vectorSize);
            vectors.put("distance", "Cosine");
            body.put("vectors", vectors);

            String createUrl = baseUrl + "/collections/" + collection;
            webClient.put()
                    .uri(createUrl)
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(body)
                    .retrieve()
                    .bodyToMono(String.class)
                    .block();

            log.info("[QDRANT] collection created. collection={}, size={}", collection, vectorSize);
        }
    }

    /**
     * 특정 documentId에 해당하는 포인트를 삭제한다.
     *
     * - Qdrant의 delete-by-filter 기능을 사용한다.
     * - upsert 시 payload에 넣어둔 document_id 필드를 조건으로 잡는다.
     *
     * API:
     * POST {baseUrl}/collections/{collection}/points/delete?wait=true
     */
    public QdrantModels.DeletePointsResponse deleteByDocument(
            String baseUrl,
            String collection,
            String documentId,
            String traceId) {
        String url = baseUrl + "/collections/" + collection + "/points/delete?wait=true";

        // filter: payload.document_id == documentId 인 포인트들을 삭제
        QdrantModels.DeletePointsRequest req = new QdrantModels.DeletePointsRequest(
                new QdrantModels.Filter(
                        List.of(new QdrantModels.FieldCondition("document_id",
                                new QdrantModels.MatchValue(documentId))),
                        null,
                        null));

        try {
            return webClient.post()
                    .uri(url)
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(req)
                    .retrieve()
                    .bodyToMono(QdrantModels.DeletePointsResponse.class)
                    .block();
        } catch (WebClientResponseException e) {
            // Qdrant가 4xx/5xx 응답을 주면 로그에 status/body를 남겨 원인 파악에 도움
            log.error("[QDRANT][{}] delete failed. status={}, body={}", traceId, e.getStatusCode(),
                    e.getResponseBodyAsString());
            throw e;
        }
    }

    /**
     * 벡터 포인트 업서트(삽입/갱신).
     *
     * API:
     * PUT {baseUrl}/collections/{collection}/points?wait=true
     *
     * - req에는 points(id, vector, payload)가 들어간다.
     * - wait=true라서 Qdrant가 처리 완료까지 기다린 후 응답한다.
     */
    public QdrantModels.UpsertResponse upsert(String baseUrl, String collection, QdrantModels.UpsertRequest req,
            String traceId) {
        String url = baseUrl + "/collections/" + collection + "/points?wait=true";

        try {
            return webClient.put()
                    .uri(url)
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(req)
                    .retrieve()
                    .bodyToMono(QdrantModels.UpsertResponse.class)
                    .block();
        } catch (WebClientResponseException e) {
            log.error("[QDRANT][{}] upsert failed. status={}, body={}",
                    traceId, e.getStatusCode(), e.getResponseBodyAsString());
            throw e;
        }
    }

    public QdrantModels.SearchResponse search(float[] vector, int limit, UUID userNo) {
        return search(vector, limit, userNo, null, null);
    }

    /**
     * 벡터 유사 검색.
     * - {@code categoryId}: payload.category_id 일치 (필수 권장)
     * - {@code documentId}: payload.document_id 일치 (선택)
     */
    public QdrantModels.SearchResponse search(
            float[] vector, int limit, UUID userNo, String categoryId, String documentId) {
        try {
            // float[] → List<Float> (Qdrant 요청 형식 맞춤)
            List<Float> vec = new ArrayList<>(vector.length);
            for (float v : vector)
                vec.add(v);

            String url = props.qdrant().baseUrl()
                    + "/collections/" + props.qdrant().collection()
                    + "/points/search";

            QdrantModels.Filter tenantScopedFilter =
                    buildTenantSearchFilter(userNo, categoryId, documentId);

            return webClient.post()
                    .uri(url)
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(new QdrantModels.SearchRequest(vec, limit, true, false, tenantScopedFilter))
                    .retrieve()
                    .bodyToMono(QdrantModels.SearchResponse.class)
                    .timeout(Duration.ofMillis(resolveTimeoutMs()))
                    .block();
        } catch (WebClientResponseException.BadRequest e) {
            log.warn("[RAG] qdrant search bad request. retry with fallback filter. body={}", e.getResponseBodyAsString());
            return searchWithFallbackMustFilter(vector, limit, userNo, categoryId, documentId);
        } catch (Exception e) {
            log.error("[RAG] qdrant search failed. limit={}, reason={}", limit, e.getMessage(), e);
            throw e;
        }
    }

    /**
     * 검색 접근 필터.
     * <ul>
     *   <li>개인({@link PersonalCategoryIds}): {@code user_type=user} AND {@code user_no=요청자}</li>
     *   <li>사내: {@code category_id} 일치만 (업로더·user_type 무관)</li>
     * </ul>
     */
    private QdrantModels.Filter buildTenantSearchFilter(
            UUID userNo, String categoryId, String documentId) {
        List<QdrantModels.FieldCondition> must = new ArrayList<>();
        if (documentId != null && !documentId.isBlank()) {
            must.add(new QdrantModels.FieldCondition("document_id", new QdrantModels.MatchValue(documentId.trim())));
        }

        String personalId = configuredPersonalCategoryId();
        if (!PersonalCategoryIds.isPersonal(categoryId, personalId)) {
            must.add(new QdrantModels.FieldCondition("category_id", new QdrantModels.MatchValue(categoryId.trim())));
            // 사내 문서는 user_type=admin으로 저장된다.
            // RAG_PERSONAL_CATEGORY_ID 미설정 등으로 개인 카테고리가 사내 경로로 오인될 때
            // user_type=user 인 개인 문서가 누출되는 것을 차단한다.
            must.add(new QdrantModels.FieldCondition("user_type", new QdrantModels.MatchValue("admin")));
            return new QdrantModels.Filter(must, null, null);
        }
        must.add(new QdrantModels.FieldCondition("user_type", new QdrantModels.MatchValue("user")));
        must.add(new QdrantModels.FieldCondition("user_no", new QdrantModels.MatchValue(userNo.toString())));
        return new QdrantModels.Filter(must, null, null);
    }

    private String configuredPersonalCategoryId() {
        if (props.categories() == null || props.categories().personalCategoryId() == null
                || props.categories().personalCategoryId().isBlank()) {
            return null;
        }
        return props.categories().personalCategoryId().trim();
    }

    private QdrantModels.SearchResponse searchWithFallbackMustFilter(
            float[] vector,
            int limit,
            UUID userNo,
            String categoryId,
            String documentId
    ) {
        List<Float> vec = new ArrayList<>(vector.length);
        for (float v : vector) vec.add(v);

        String url = props.qdrant().baseUrl()
                + "/collections/" + props.qdrant().collection()
                + "/points/search";

        QdrantModels.Filter fallback = buildTenantSearchFilter(userNo, categoryId, documentId);

        return webClient.post()
                .uri(url)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(new QdrantModels.SearchRequest(vec, limit, true, false, fallback))
                .retrieve()
                .bodyToMono(QdrantModels.SearchResponse.class)
                .timeout(Duration.ofMillis(resolveTimeoutMs()))
                .block();
    }

    private long resolveTimeoutMs() {
        if (props == null || props.qdrant() == null || props.qdrant().timeoutMs() <= 0) {
            return DEFAULT_TIMEOUT_MS;
        }
        return props.qdrant().timeoutMs();
    }
}
