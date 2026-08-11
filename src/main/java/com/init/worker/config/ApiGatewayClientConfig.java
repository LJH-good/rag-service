package com.init.worker.config;

import com.init.worker.service.AiEmbeddingClient;
import com.init.worker.service.AiQaClient;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.web.reactive.function.client.ExchangeFilterFunction;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;
import reactor.netty.http.client.HttpClient;

import java.time.Duration;

/** API 프로필 — Gateway 경유 AIG 호출 (임베딩 TX-C, QA TX-A). */
@Slf4j
@Configuration
@ConditionalOnProperty(name = "rag.app.role", havingValue = "api")
public class ApiGatewayClientConfig {

    @Bean(name = "apiGatewayQaWebClient")
    public WebClient apiGatewayQaWebClient(RagProperties props) {
        return buildGatewayWebClient(
                props,
                props.langchainService().timeoutMs() > 0
                        ? props.langchainService().timeoutMs()
                        : 120_000L,
                "API_GATEWAY_QA");
    }

    @Bean
    public AiQaClient aiQaClient(@Qualifier("apiGatewayQaWebClient") WebClient apiGatewayQaWebClient) {
        return new AiQaClient(apiGatewayQaWebClient);
    }

    @Bean(name = "apiGatewayEmbedWebClient")
    public WebClient apiGatewayEmbedWebClient(RagProperties props) {
        return buildGatewayWebClient(
                props,
                props.gateway().embeddingTimeoutSeconds() * 1000L,
                "API_GATEWAY_EMBED");
    }

    @Bean
    public AiEmbeddingClient aiEmbeddingClient(
            @Qualifier("apiGatewayEmbedWebClient") WebClient apiGatewayEmbedWebClient,
            RagProperties ragProperties
    ) {
        return new AiEmbeddingClient(apiGatewayEmbedWebClient, ragProperties);
    }

    private WebClient buildGatewayWebClient(RagProperties props, long timeoutMs, String logName) {
        String baseUrl = props.gateway().baseUrl();
        if (baseUrl == null || baseUrl.isBlank()) {
            throw new IllegalStateException(
                    "rag.app.role=api 이지만 rag.gateway.base-url 이 비어 있습니다. AIG 호출(Gateway 경유)에 필요합니다.");
        }
        int maxInMemory = props.gateway().maxInMemorySizeBytes();
        return WebClient.builder()
                .baseUrl(baseUrl.trim())
                .clientConnector(new ReactorClientHttpConnector(
                        HttpClient.create().responseTimeout(Duration.ofMillis(timeoutMs))
                ))
                .filter(logRequest(logName))
                .codecs(configurer ->
                        configurer.defaultCodecs().maxInMemorySize(maxInMemory)
                )
                .build();
    }

    private ExchangeFilterFunction logRequest(String clientName) {
        return ExchangeFilterFunction.ofRequestProcessor(request -> {
            request.headers().forEach((name, values) ->
                    log.debug("### [{}] REQUEST HEADER {}={}", clientName, name, values)
            );
            return Mono.just(request);
        });
    }
}
