package com.ragservice.worker.service;

import com.ragservice.worker.config.RagProperties;
import com.ragservice.worker.dto.EmbeddingBatchRequest;
import com.ragservice.worker.dto.EmbeddingBatchResponse;
import com.ragservice.worker.dto.EmbeddingInput;
import com.ragservice.worker.dto.EmbeddingResult;
import com.ragservice.worker.error.code.ErrorCodes;
import com.ragservice.worker.error.exception.AppException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatusCode;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientRequestException;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.core.publisher.Mono;
import reactor.core.Exceptions;

import java.time.Duration;
import java.util.concurrent.TimeoutException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

@Slf4j
public class AiEmbeddingClient {

    private static final String API_KEY_HEADER = "X-API-Key";
    private static final String TX_ID_HEADER = "X-Transaction-Id";

    private final WebClient gatewayEmbedWebClient;
    private final RagProperties props;

    /** 동시 다문서 EMBED 시 OpenAI 429/503 burst 방지 — 프로세스 내 임베딩 호출 직렬화 */
    private final Object embedRequestLock = new Object();

    public AiEmbeddingClient(WebClient gatewayEmbedWebClient, RagProperties props) {
        this.gatewayEmbedWebClient = gatewayEmbedWebClient;
        this.props = props;
        RagProperties.Gateway gateway = props.gateway();
        log.info("### AiEmbeddingClient initialized. route=gateway-embed, gatewayBaseUrl={}, "
                        + "embeddingTimeoutSeconds={}, embeddingRateLimitRetryCount={}, "
                        + "embeddingRateLimitRetryBaseDelayMs={}",
                gateway.baseUrl(),
                gateway.embeddingTimeoutSeconds(),
                gateway.embeddingRateLimitRetryCount(),
                gateway.embeddingRateLimitRetryBaseDelayMs());
    }

    public List<float[]> requestDocumentEmbeddings(
            String aiServiceName,
            UUID transactionId,
            List<EmbeddingInput> inputs,
            String userApiKey
    ) {
        synchronized (embedRequestLock) {
            int maxAttempts = 1 + Math.max(0, props.gateway().embeddingRateLimitRetryCount());
            EmbeddingRequestException last = null;

            for (int attempt = 1; attempt <= maxAttempts; attempt++) {
                try {
                    return requestDocumentEmbeddingsOnce(aiServiceName, transactionId, inputs, userApiKey);
                } catch (EmbeddingRequestException e) {
                    last = e;
                    if (attempt >= maxAttempts || !isRetryableTransient(e)) {
                        throw e;
                    }
                    long waitMs = backoffWaitMs(attempt);
                    log.warn("[EMBED][{}] embedding transient failure (attempt {}/{}), waiting {} ms then retry. reason={}",
                            transactionId, attempt, maxAttempts, waitMs, e.getMessage());
                    sleepQuietly(waitMs);
                }
            }
            throw last != null ? last : new EmbeddingRequestException(
                    "EMBED_EXCEPTION", "embedding request failed. txId=" + transactionId, null);
        }
    }

    private List<float[]> requestDocumentEmbeddingsOnce(
            String aiServiceName,
            UUID transactionId,
            List<EmbeddingInput> inputs,
            String userApiKey
    ) {
        EmbeddingBatchRequest request = new EmbeddingBatchRequest(inputs);

        try {
            EmbeddingBatchResponse response = gatewayEmbedWebClient.post()
                    .uri(uriBuilder -> uriBuilder
                            .path("/api/ai/{aiServiceName}/embedding/request")
                            .build(aiServiceName)
                    )
                    .header(API_KEY_HEADER, userApiKey)
                    .header(TX_ID_HEADER, transactionId.toString())
                    .bodyValue(request)
                    .retrieve()
                    .onStatus(HttpStatusCode::isError, clientResponse ->
                            clientResponse.bodyToMono(String.class)
                                    .defaultIfEmpty("")
                                    .flatMap(body -> {
                                        log.error("[EMBED][{}] embedding request failed. status={}, body={}",
                                                transactionId, clientResponse.statusCode(), body);
                                        return Mono.error(new AppException(
                                                ErrorCodes.Api.EMBEDDING_API_REQUEST_FAILED,
                                                Map.of(
                                                        "status", clientResponse.statusCode().toString(),
                                                        "body", body
                                                )
                                        ));
                                    })
                    )
                    .bodyToMono(EmbeddingBatchResponse.class)
                    .timeout(Duration.ofSeconds(props.gateway().embeddingTimeoutSeconds()))
                    .block();

            validateResponse(response, inputs);

            Map<String, EmbeddingResult> resultMap = response.results().stream()
                    .collect(Collectors.toMap(
                            EmbeddingResult::itemId,
                            Function.identity(),
                            (a, b) -> a
                    ));

            List<float[]> ordered = new ArrayList<>(inputs.size());
            for (EmbeddingInput input : inputs) {
                EmbeddingResult result = resultMap.get(input.itemId());

                if (result == null || result.embedding() == null || result.embedding().length == 0) {
                    throw new IllegalStateException("embedding result missing for itemId=" + input.itemId());
                }

                ordered.add(result.embedding());
            }

            return ordered;
        } catch (Exception e) {
            EmbeddingFailureDetail detail = describeFailure(e);
            log.error("[EMBED][{}] {} [{}]", transactionId, detail.message(), detail.code(), e);
            throw new EmbeddingRequestException(detail.code(), detail.message(), e);
        }
    }

    private long backoffWaitMs(int failedAttemptIndex) {
        int exp = Math.max(0, failedAttemptIndex - 1);
        long mult = 1L << Math.min(exp, 20);
        long raw = props.gateway().embeddingRateLimitRetryBaseDelayMs() * mult;
        return Math.min(props.gateway().embeddingRateLimitRetryMaxDelayMs(), raw);
    }

    private static void sleepQuietly(long waitMs) {
        try {
            Thread.sleep(waitMs);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted during embedding retry backoff", ie);
        }
    }

    /** RagEmbedWorker 재큐잉 판단과 동일한 기준. */
    public static boolean isTransientEmbeddingFailure(Throwable e) {
        return isRetryableTransient(e);
    }

    private static boolean isRetryableTransient(Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof TimeoutException
                    || t instanceof WebClientRequestException
                    || Exceptions.isRetryExhausted(t)) {
                return true;
            }
            if (t instanceof AppException ae
                    && ae.getErrorCode() == ErrorCodes.Api.EMBEDDING_API_REQUEST_FAILED) {
                Map<String, Object> args = ae.getArgs();
                if (args == null) {
                    continue;
                }
                String status = String.valueOf(args.getOrDefault("status", ""));
                String body = String.valueOf(args.getOrDefault("body", ""));
                if (status.contains("429")
                        || status.contains("503")
                        || status.contains("502")
                        || status.contains("504")) {
                    return true;
                }
                if (body.contains("AI_SERVICE.RATE_LIMITED")
                        || body.contains("RATE_LIMIT")
                        || body.contains("rate limit")
                        || body.contains("overloaded")
                        || body.contains("temporarily unavailable")) {
                    return true;
                }
            }
        }
        return false;
    }

    /** RagEmbedWorker 가 job.error_message 에 남길 임베딩 실패 요약. */
    public record EmbeddingFailureDetail(String code, String message) {}

    /**
     * 예외 cause chain 을 따라 HTTP status/body·응답 검증 오류·연결/타임아웃 등
     * 실제 원인을 job.error_message 에 실을 수 있는 형태로 정리한다.
     */
    public static EmbeddingFailureDetail describeFailure(Throwable e) {
        if (e == null) {
            return new EmbeddingFailureDetail("EMBED_EXCEPTION", "embed exception: unknown");
        }
        if (e instanceof EmbeddingRequestException ere) {
            return new EmbeddingFailureDetail(ere.getErrorCode(), truncate(ere.getMessage(), 800));
        }
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof EmbeddingRequestException ere) {
                return new EmbeddingFailureDetail(ere.getErrorCode(), truncate(ere.getMessage(), 800));
            }
            if (t instanceof AppException ae) {
                if (ae.getErrorCode() == ErrorCodes.Api.EMBEDDING_API_REQUEST_FAILED) {
                    Map<String, Object> args = ae.getArgs();
                    String status = args != null ? String.valueOf(args.getOrDefault("status", "")) : "";
                    String body = args != null ? String.valueOf(args.getOrDefault("body", "")) : "";
                    return new EmbeddingFailureDetail(
                            "EMBEDDING_API_REQUEST_FAILED",
                            "embedding api failed. status=" + status + ", body=" + truncate(body, 800));
                }
                return new EmbeddingFailureDetail(ae.getCode(), truncate(ae.getMessage(), 800));
            }
            if (t instanceof WebClientResponseException wcre) {
                return new EmbeddingFailureDetail(
                        "EMBEDDING_API_REQUEST_FAILED",
                        "embedding api failed. status=" + wcre.getStatusCode()
                                + ", body=" + truncate(wcre.getResponseBodyAsString(), 800));
            }
            if (t instanceof TimeoutException) {
                return new EmbeddingFailureDetail(
                        "EMBEDDING_API_TIMEOUT",
                        "embedding api timeout: " + truncate(t.getMessage(), 400));
            }
            if (t instanceof WebClientRequestException wcre) {
                return new EmbeddingFailureDetail(
                        "EMBEDDING_API_CONNECTION_FAILED",
                        "embedding api connection failed: " + truncate(wcre.getMessage(), 400));
            }
            if (t instanceof IllegalStateException ise && ise.getMessage() != null
                    && ise.getMessage().contains("embedding result missing for itemId=")) {
                return new EmbeddingFailureDetail(
                        "EMBEDDING_RESULT_MISSING",
                        truncate(ise.getMessage(), 800));
            }
            if (isEmbeddingWrapper(t)) {
                continue;
            }
        }
        return new EmbeddingFailureDetail(
                "EMBED_EXCEPTION",
                "embed exception: " + formatCauseChain(e));
    }

    private static boolean isEmbeddingWrapper(Throwable t) {
        if (!(t instanceof IllegalStateException ise) || ise.getMessage() == null) {
            return false;
        }
        String msg = ise.getMessage();
        return msg.startsWith("embedding request failed. txId=")
                || msg.startsWith("embed exception:");
    }

    private static String formatCauseChain(Throwable e) {
        StringBuilder chain = new StringBuilder();
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (isEmbeddingWrapper(t)) {
                continue;
            }
            if (chain.length() > 0) {
                chain.append(" -> ");
            }
            String msg = t.getMessage();
            chain.append(t.getClass().getSimpleName());
            if (msg != null && !msg.isBlank()) {
                chain.append(": ").append(truncate(msg, 300));
            }
        }
        if (chain.length() == 0) {
            return e.getClass().getSimpleName();
        }
        return truncate(chain.toString(), 800);
    }

    private static String truncate(String value, int maxLen) {
        if (value == null) {
            return "";
        }
        return value.length() > maxLen ? value.substring(0, maxLen) + "..." : value;
    }

    private void validateResponse(
            EmbeddingBatchResponse response,
            List<EmbeddingInput> inputs
    ) {
        if (response == null) {
            throw new AppException(ErrorCodes.Api.EMBEDDING_API_RESPONSE_NULL);
        }

        if (response.results() == null || response.results().isEmpty()) {
            throw new AppException(ErrorCodes.Api.EMBEDDING_API_RESPONSE_EMPTY);
        }

        if (response.results().size() != inputs.size()) {
            throw new AppException(
                    ErrorCodes.Api.EMBEDDING_API_RESPONSE_SIZE_MISMATCH,
                    Map.of("expected", inputs.size(), "actual", response.results().size())
            );
        }
    }
}
