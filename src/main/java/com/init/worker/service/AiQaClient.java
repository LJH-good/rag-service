package com.init.worker.service;

import com.init.worker.dto.AskRequest;
import com.init.worker.dto.CitationDto;
import com.init.worker.dto.QaAskResponse;
import com.init.worker.dto.StreamChunkDTO;
import com.init.worker.error.code.ErrorCodes;
import com.init.worker.error.exception.AppException;
import com.init.worker.error.exception.UpstreamErrorException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.core.publisher.Mono;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** AI Gateway POST /api/ai/{svc}/chat/stream (RAG QA 모드) — Control Plane Gateway 경유. */
@Slf4j
public class AiQaClient {

    private static final String API_KEY_HEADER = "X-API-Key";
    private static final String USER_NO_HEADER = "X-User-No";
    private static final String TX_ID_HEADER = "X-Transaction-Id";

    private final WebClient gatewayWebClient;

    public AiQaClient(WebClient gatewayWebClient) {
        this.gatewayWebClient = gatewayWebClient;
    }

    public QaAskResponse qa(
            String aiServiceName,
            AskRequest request,
            List<CitationDto> citations,
            String userApiKey,
            UUID userNo,
            UUID transactionId
    ) {
        try {
            final StringBuilder answerBuilder = new StringBuilder();
            final StreamChunkDTO[] lastChunkHolder = { null };
            final String[] modelHolder = { null };
            final String[] providerHolder = { null };

            gatewayWebClient.post()
                    .uri("/api/ai/{aiServiceName}/chat/stream", aiServiceName)
                    .header(API_KEY_HEADER, userApiKey)
                    .header(USER_NO_HEADER, userNo.toString())
                    .header(TX_ID_HEADER, transactionId.toString())
                    .contentType(MediaType.APPLICATION_JSON)
                    .accept(MediaType.TEXT_EVENT_STREAM)
                    .bodyValue(toChatStreamBody(request, citations))
                    .retrieve()
                    .onStatus(HttpStatusCode::isError, resp ->
                            resp.bodyToMono(String.class).defaultIfEmpty("")
                                    .flatMap(body -> {
                                        log.error("[RAG][{}][AI_QA] HTTP {} body={}",
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
                    .doOnSubscribe(s -> log.info("[RAG][{}][AI_QA] start aiService={} via chat/stream",
                            transactionId, aiServiceName))
                    .doOnNext(chunk -> {
                        if (chunk.getContent() != null) {
                            answerBuilder.append(chunk.getContent());
                        }
                        if (chunk.getModel() != null) {
                            modelHolder[0] = chunk.getModel();
                        }
                        if (chunk.getProvider() != null) {
                            providerHolder[0] = chunk.getProvider();
                        }
                        if (Boolean.TRUE.equals(chunk.getIsLast())) {
                            lastChunkHolder[0] = chunk;
                        }
                    })
                    .blockLast();

            String answer = answerBuilder.toString().trim();
            if (answer.isBlank()) {
                throw new AppException(ErrorCodes.Api.CHAT_API_RESPONSE_EMPTY);
            }

            StreamChunkDTO lastChunk = lastChunkHolder[0];
            UUID messageId = lastChunk != null && lastChunk.getMessageId() != null
                    ? lastChunk.getMessageId()
                    : request.messageId();
            UUID sessionId = lastChunk != null && lastChunk.getSessionId() != null
                    ? lastChunk.getSessionId()
                    : request.sessionId();
            List<CitationDto> responseCitations = lastChunk != null && lastChunk.getCitations() != null
                    ? lastChunk.getCitations()
                    : List.of();
            String model = lastChunk != null && lastChunk.getModel() != null
                    ? lastChunk.getModel()
                    : modelHolder[0];
            String provider = lastChunk != null && lastChunk.getProvider() != null
                    ? lastChunk.getProvider()
                    : providerHolder[0];

            return new QaAskResponse(
                    messageId,
                    sessionId,
                    answer,
                    model,
                    provider,
                    responseCitations
            );
        } catch (WebClientResponseException e) {
            throw new UpstreamErrorException(e.getStatusCode().value(), e.getResponseBodyAsString(), e);
        }
    }

    private static Map<String, Object> toChatStreamBody(AskRequest request, List<CitationDto> citations) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("ragQa", true);
        body.put("sessionId", request.sessionId().toString());
        body.put("messageId", request.messageId().toString());
        body.put("content", request.content());
        if (request.modelCode() != null && !request.modelCode().isBlank()) {
            body.put("modelCode", request.modelCode());
        }
        if (citations != null) {
            body.put("citations", citations);
        }
        return body;
    }
}
