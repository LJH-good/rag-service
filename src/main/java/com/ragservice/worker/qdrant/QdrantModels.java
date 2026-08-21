package com.ragservice.worker.qdrant;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;
import java.util.Map;

/**
 * Qdrant REST API 요청/응답 바디를 직렬화/역직렬화하기 위한 모델 모음.
 *
 * 포인트 업서트(upsert)와 필터 기반 삭제(delete)에서 필요한 최소 스키마만 정의했다.
 * - Jackson(@JsonProperty)으로 Qdrant가 기대하는 JSON 필드명에 맞춘다.
 * - null 값은 보내지 않도록(@JsonInclude NON_NULL) 처리해 불필요한 필드 전송을 줄인다.
 */
public class QdrantModels {

    /** /points/search 요청 바디 */
    public record SearchRequest(
            List<Float> vector,
            int limit,
            boolean with_payload,
            boolean with_vector,
            Filter filter
    ) {}

    /** /points/search 응답 바디 (Qdrant: result 는 배열) */
    public record SearchResponse(
            List<Point> result,
            String status,
            double time
    ) {
        public record Point(
                Object id,                 // 숫자/문자(UUID) 둘 다 올 수 있어 Object 권장
                double score,
                Map<String, Object> payload
        ) {}
    }

    /**
     * Qdrant REST API 요청/응답 바디를 직렬화/역직렬화하기 위한 모델 모음.
     *
     * 포인트 업서트(upsert)와 필터 기반 삭제(delete)에서 필요한 최소 스키마만 정의했다.
     * - Jackson(@JsonProperty)으로 Qdrant가 기대하는 JSON 필드명에 맞춘다.
     * - null 값은 보내지 않도록(@JsonInclude NON_NULL) 처리해 불필요한 필드 전송을 줄인다.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Point(
            @JsonProperty("id") String id,                 // UUID string
            @JsonProperty("vector") List<Float> vector,    // 1536 floats
            @JsonProperty("payload") Map<String, Object> payload
    ) {}

    /**
     * 업서트 요청 바디.
     * - points 배열에 삽입/갱신할 포인트들을 담는다.
     */
    public record UpsertRequest(
            @JsonProperty("points") List<Point> points
    ) {}

    /**
     * 업서트 응답 바디(간단 버전).
     * - status/time 정도만 사용하고 result는 구조가 다양해 Object로 둔다.
     */
    public record UpsertResponse(
            @JsonProperty("status") String status,
            @JsonProperty("result") Object result,
            @JsonProperty("time") Double time
    ) {}

    /**
     * match 조건의 값 래퍼.
     * - Qdrant 필터 스펙에서 match.value 형태를 만들기 위해 둔다.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record MatchValue(@JsonProperty("value") Object value) {}

    /**
     * 필드 조건(예: payload의 특정 key가 특정 값과 매치).
     * - key: payload 키 (예: "document_id")
     * - match: 매치 조건 (예: value = documentId)
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record FieldCondition(
            @JsonProperty("key") String key,
            @JsonProperty("match") MatchValue match
    ) {}

    /**
     * 필드 조건(예: payload의 특정 key가 특정 값과 매치).
     * - key: payload 키 (예: "document_id")
     * - match: 매치 조건 (예: value = documentId)
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Filter(
            @JsonProperty("must") List<FieldCondition> must,
            @JsonProperty("should") List<FieldCondition> should,
            @JsonProperty("minimum_should_match") Integer minimumShouldMatch
    ) {}

    /**
     * 포인트 삭제 요청 바디.
     * - filter 조건에 맞는 포인트들을 삭제한다.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record DeletePointsRequest(@JsonProperty("filter") Filter filter) {}

    /**
     * 포인트 삭제 응답 바디(간단 버전).
     * - status/time 정도만 사용하고 result는 구조가 다양해 Object로 둔다.
     */
    public record DeletePointsResponse(
            @JsonProperty("status") String status,
            @JsonProperty("result") Object result,
            @JsonProperty("time") Double time
    ) {}
}
