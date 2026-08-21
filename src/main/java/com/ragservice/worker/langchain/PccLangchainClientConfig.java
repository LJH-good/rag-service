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
import reactor.core.publisher.Mono;
import reactor.netty.http.client.HttpClient;

import java.time.Duration;

/**
 * {@code rag.pcc.langchain-enabled=true} 일 때 LangChain PCC용 WebClient 및 클라이언트를 등록한다.
 */
@Slf4j
@Configuration
@ConditionalOnProperty(prefix = "rag.pcc", name = "langchain-enabled", havingValue = "true")
public class PccLangchainClientConfig {

    @Bean(name = "pccLangchainWebClient")
    public WebClient pccLangchainWebClient(RagProperties props) {
        String baseUrl = props.pcc().baseUrl();
        if (baseUrl == null || baseUrl.isBlank()) {
            throw new IllegalStateException("rag.pcc.langchain-enabled=true 이지만 rag.pcc.base-url 이 비어 있습니다.");
        }
        int maxInMemory = props.pcc().maxInMemorySizeBytes();
        long timeoutMs = props.pcc().timeoutMs();
        return WebClient.builder()
                .baseUrl(baseUrl.trim())
                .clientConnector(new ReactorClientHttpConnector(
                        HttpClient.create().responseTimeout(Duration.ofMillis(timeoutMs))
                ))
                .filter(logRequest("LANGCHAIN_PCC"))
                .codecs(configurer ->
                        configurer.defaultCodecs().maxInMemorySize(maxInMemory)
                )
                .build();
    }

    @Bean
    public LangchainPccClient langchainPccClient(
            @Qualifier("pccLangchainWebClient") WebClient pccLangchainWebClient,
            RagProperties ragProperties
    ) {
        return new LangchainPccClient(pccLangchainWebClient, ragProperties);
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
