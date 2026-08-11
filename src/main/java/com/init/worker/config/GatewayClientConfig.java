package com.init.worker.config;

import com.init.worker.service.AiEmbeddingClient;
import com.init.worker.service.RagGraphExtractionClient;
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

/** Consumer 프로필 — Gateway 경유 AIG 임베딩 (EMBED). */
@Slf4j
@Configuration
@ConditionalOnProperty(name = "rag.app.role", havingValue = "consumer")
public class GatewayClientConfig {

    @Bean(name = "gatewayEmbedWebClient")
    public WebClient gatewayEmbedWebClient(RagProperties props) {
        String baseUrl = props.gateway().baseUrl();
        if (baseUrl == null || baseUrl.isBlank()) {
            throw new IllegalStateException("rag.app.role=consumer 이지만 rag.gateway.base-url 이 비어 있습니다.");
        }
        long timeoutMs = props.gateway().embeddingTimeoutSeconds() * 1000L;
        int maxInMemory = props.gateway().maxInMemorySizeBytes();
        return WebClient.builder()
                .baseUrl(baseUrl.trim())
                .clientConnector(new ReactorClientHttpConnector(
                        HttpClient.create().responseTimeout(Duration.ofMillis(timeoutMs))
                ))
                .filter(logRequest("GATEWAY_EMBED"))
                .codecs(configurer ->
                        configurer.defaultCodecs().maxInMemorySize(maxInMemory)
                )
                .build();
    }

    @Bean
    public AiEmbeddingClient aiEmbeddingClient(
            @Qualifier("gatewayEmbedWebClient") WebClient gatewayEmbedWebClient,
            RagProperties ragProperties
    ) {
        return new AiEmbeddingClient(gatewayEmbedWebClient, ragProperties);
    }

    @Bean(name = "gatewayGraphWebClient")
    public WebClient gatewayGraphWebClient(RagProperties props) {
        String baseUrl = props.gateway().baseUrl();
        if (baseUrl == null || baseUrl.isBlank()) {
            throw new IllegalStateException("rag.app.role=consumer 이지만 rag.gateway.base-url 이 비어 있습니다.");
        }
        long timeoutMs = props.graph().timeoutSeconds() * 1000L;
        int maxInMemory = props.gateway().maxInMemorySizeBytes();
        return WebClient.builder()
                .baseUrl(baseUrl.trim())
                .clientConnector(new ReactorClientHttpConnector(
                        HttpClient.create().responseTimeout(Duration.ofMillis(timeoutMs))
                ))
                .filter(logRequest("GATEWAY_GRAPH"))
                .codecs(configurer ->
                        configurer.defaultCodecs().maxInMemorySize(maxInMemory)
                )
                .build();
    }

    @Bean
    public RagGraphExtractionClient ragGraphExtractionClient(
            @Qualifier("gatewayGraphWebClient") WebClient gatewayGraphWebClient,
            RagProperties props
    ) {
        return new RagGraphExtractionClient(gatewayGraphWebClient, props.graph().timeoutSeconds());
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
