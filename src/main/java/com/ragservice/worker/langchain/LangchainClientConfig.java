package com.ragservice.worker.langchain;

import com.ragservice.worker.config.RagProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.web.reactive.function.client.ExchangeFilterFunction;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.netty.http.client.HttpClient;
import reactor.core.publisher.Mono;

import java.time.Duration;

/**
 * {@code rag.langchain-service.enabled=true} 일 때 langchain-service WebClient 및 QA 클라이언트를 등록한다.
 */
@Slf4j
@Configuration
@ConditionalOnProperty(name = "rag.langchain-service.enabled", havingValue = "true")
public class LangchainClientConfig {

    @Bean(name = "langchainWebClient")
    public WebClient langchainWebClient(RagProperties props) {
        String baseUrl = props.langchainService().baseUrl();
        if (baseUrl == null || baseUrl.isBlank()) {
            throw new IllegalStateException("rag.langchain-service.enabled=true 이지만 rag.langchain-service.base-url 이 비어 있습니다.");
        }
        int maxInMemory = props.langchainService().maxInMemorySizeBytes();
        long timeoutMs = props.langchainService().timeoutMs();
        return WebClient.builder()
                .baseUrl(baseUrl.trim())
                .clientConnector(new ReactorClientHttpConnector(
                        HttpClient.create().responseTimeout(Duration.ofMillis(timeoutMs))
                ))
                .filter(logRequest("LANGCHAIN_SVC"))
                .codecs(configurer ->
                        configurer.defaultCodecs().maxInMemorySize(maxInMemory)
                )
                .build();
    }

    @Bean
    public LangchainQaClient langchainQaClient(
            @Qualifier("langchainWebClient") WebClient langchainWebClient,
            RagProperties ragProperties
    ) {
        return new LangchainQaClient(langchainWebClient, ragProperties);
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
