package com.init.worker.langchain;

import com.init.worker.config.RagProperties;
import com.init.worker.dto.AskRequest;
import com.init.worker.dto.AskResponse;
import com.init.worker.dto.CitationDto;
import com.init.worker.error.exception.UpstreamErrorException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import org.springframework.web.util.UriComponentsBuilder;
import reactor.core.publisher.Mono;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * QA 요청을 langchain-service로 위임하는 클라이언트.
 */
@Slf4j
public class LangchainQaClient {

    private static final String TX_ID_HEADER = "X-Transaction-Id";
    private static final String API_KEY_HEADER = "X-API-Key";
    private static final String USER_NO_HEADER = "X-User-No";

    private final WebClient langchainWebClient;
    private final RagProperties ragProperties;

    public LangchainQaClient(WebClient langchainWebClient, RagProperties ragProperties) {
        this.langchainWebClient = langchainWebClient;
        this.ragProperties = ragProperties;
    }

    public AskResponse ask(
            String aiServiceName,
            AskRequest req,
            String userApiKey,
            UUID userNo,
            UUID transactionId
    ) {
        String path = resolveQaPath(aiServiceName);
        LangchainAskRequest body = LangchainAskRequest.from(req);
        LangchainAskResponse lcRes;
        try {
            lcRes = langchainWebClient.post()
                    .uri(path)
                    .header(API_KEY_HEADER, userApiKey)
                    .header(USER_NO_HEADER, userNo.toString())
                    .header(TX_ID_HEADER, transactionId.toString())
                    .contentType(MediaType.APPLICATION_JSON)
                    .accept(MediaType.APPLICATION_JSON)
                    .bodyValue(body)
                    .retrieve()
                    .onStatus(HttpStatusCode::isError, response ->
                            response.bodyToMono(String.class)
                                    .defaultIfEmpty("")
                                    .flatMap(errBody -> {
                                        log.error("[RAG][{}][LANGCHAIN_QA] HTTP {} path={} body={}",
                                                transactionId, response.statusCode(), path, errBody);
                                        return Mono.error(WebClientResponseException.create(
                                                response.statusCode().value(),
                                                response.statusCode().toString(),
                                                response.headers().asHttpHeaders(),
                                                errBody.getBytes(),
                                                null
                                        ));
                                    })
                    )
                    .bodyToMono(String.class)
                    .doOnSubscribe(s -> log.info("[RAG][{}][LANGCHAIN_QA] start. path={}", transactionId, path))
                    .doOnSuccess(raw -> log.info("[RAG][{}][LANGCHAIN_QA] raw={}",
                            transactionId, raw == null ? "(null)" : raw.length() > 2000 ? raw.substring(0, 2000) : raw))
                    .map(raw -> {
                        if (raw == null || raw.isBlank()) return null;
                        try {
                            return new com.fasterxml.jackson.databind.ObjectMapper()
                                    .findAndRegisterModules()
                                    .readValue(raw, LangchainAskResponse.class);
                        } catch (Exception e) {
                            throw new RuntimeException("LangChain QA response parse failed: " + e.getMessage(), e);
                        }
                    })
                    .block();
        } catch (WebClientResponseException e) {
            throw new UpstreamErrorException(e.getStatusCode().value(), e.getResponseBodyAsString(), e);
        }

        UUID messageId = req.messageId();
        if (lcRes == null) {
            return new AskResponse(messageId, List.of(), req.modelCode(), null);
        }

        List<CitationDto> mappedCitations = Optional.ofNullable(lcRes.citations())
                .orElse(List.of())
                .stream()
                .map(c -> new CitationDto(
                        c.chunkId(),
                        c.documentId(),
                        c.score() != null
                                ? BigDecimal.valueOf(c.score()).setScale(4, RoundingMode.HALF_UP)
                                : BigDecimal.ZERO,
                        c.page(),
                        c.slide(),
                        c.sheet(),
                        c.sourceUri(),
                        c.text()
                ))
                .toList();

        String modelName = lcRes.modelName();
        if (modelName == null || modelName.isBlank()) {
            modelName = req.modelCode();
        }
        return new AskResponse(messageId, mappedCitations, modelName, lcRes.provider());
    }

    private String resolveQaPath(String aiServiceName) {
        String pattern = ragProperties.qa().langchainPath();
        if (pattern == null || pattern.isBlank()) {
            pattern = "/api/rag/{aiServiceName}/qa";
        }
        if (pattern.contains("{aiServiceName}")) {
            return UriComponentsBuilder.fromPath(pattern)
                    .buildAndExpand(Map.of("aiServiceName", aiServiceName == null ? "" : aiServiceName))
                    .toUriString();
        }
        return pattern;
    }
}
