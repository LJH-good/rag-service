package com.ragservice.worker.service;

import com.ragservice.worker.dto.StreamChunkDTO;
import com.ragservice.worker.error.exception.UpstreamErrorException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Graph RAG Pass1/Pass2(엔티티·관계 추출)용 LLM 호출 클라이언트.
 * <p>AI Gateway {@code POST /api/ai/{svc}/chat/stream} 를 QA 클라이언트와 동일한 방식으로 재사용하되,
 * {@code ragQa=false} + {@code ragIndexing=true} 로 호출한다.
 * {@code ragIndexing} 은 UAK 서비스 ACL을 건너뛰고(임베딩과 동일) 과금만 적용한다.
 * 서비스는 RAG 가 고르고, 구체 모델은 AIG 가 {@code modelPreference}(CHEAP/PREMIUM) 또는
 * 명시 {@code modelCode} 로 정한다.
 */
@Slf4j
public class RagGraphExtractionClient {

    private static final String API_KEY_HEADER = "X-API-Key";
    private static final String TX_ID_HEADER = "X-Transaction-Id";

    private final WebClient gatewayWebClient;
    private final long timeoutSeconds;

    public RagGraphExtractionClient(WebClient gatewayWebClient, long timeoutSeconds) {
        this.gatewayWebClient = gatewayWebClient;
        this.timeoutSeconds = timeoutSeconds > 0 ? timeoutSeconds : 900;
    }

    /**
     * 프롬프트를 LLM 에 보내고 스트림 응답 본문을 이어붙여 반환한다.
     *
     * @param modelPreference CHEAP|PREMIUM — modelCode 미지정 시 AIG priceLevel 라우팅
     * @param modelCode       null 이면 AIG 가 modelPreference 기준으로 선택
     */
    public String complete(String aiServiceName,
                           String modelPreference,
                           String modelCode,
                           String prompt,
                           String userApiKey,
                           UUID transactionId) {
        try {
            final StringBuilder answer = new StringBuilder();
            gatewayWebClient.post()
                    .uri("/api/ai/{aiServiceName}/chat/stream", aiServiceName)
                    .header(API_KEY_HEADER, userApiKey)
                    .header(TX_ID_HEADER, transactionId.toString())
                    .contentType(MediaType.APPLICATION_JSON)
                    .accept(MediaType.TEXT_EVENT_STREAM)
                    .bodyValue(toChatStreamBody(modelPreference, modelCode, prompt, transactionId))
                    .retrieve()
                    .onStatus(HttpStatusCode::isError, resp ->
                            resp.bodyToMono(String.class).defaultIfEmpty("")
                                    .flatMap(body -> {
                                        log.error("[RAG_GRAPH][{}][LLM] HTTP {} body={}",
                                                transactionId, resp.statusCode(), body);
                                        return Mono.error(WebClientResponseException.create(
                                                resp.statusCode().value(),
                                                resp.statusCode().toString(),
                                                resp.headers().asHttpHeaders(),
                                                body.getBytes(),
                                                null
                                        ));
                                    })
                    )
                    .bodyToFlux(StreamChunkDTO.class)
                    .timeout(Duration.ofSeconds(timeoutSeconds))
                    .doOnSubscribe(s -> log.info("[RAG_GRAPH][{}][LLM] start aiService={} preference={} modelCode={}",
                            transactionId, aiServiceName, modelPreference, modelCode))
                    .doOnNext(chunk -> {
                        if (chunk.getContent() != null) {
                            answer.append(chunk.getContent());
                        }
                    })
                    .blockLast();

            return answer.toString();
        } catch (WebClientResponseException e) {
            throw new UpstreamErrorException(e.getStatusCode().value(), e.getResponseBodyAsString(), e);
        }
    }

    private static Map<String, Object> toChatStreamBody(String modelPreference,
                                                        String modelCode,
                                                        String prompt,
                                                        UUID transactionId) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("ragQa", false);
        // 임베딩과 같이 제공자 ACL 없이 인덱싱용으로 호출(과금 UAK는 유지).
        body.put("ragIndexing", true);
        // chat/stream 계약상 세션/메시지 식별자가 필요하므로 배경 작업용으로 새로 발급한다.
        body.put("sessionId", transactionId.toString());
        body.put("messageId", UUID.randomUUID().toString());
        body.put("content", prompt);
        if (modelPreference != null && !modelPreference.isBlank()) {
            body.put("modelPreference", modelPreference.trim());
        }
        if (modelCode != null && !modelCode.isBlank()) {
            body.put("modelCode", modelCode);
        }
        return body;
    }
}
