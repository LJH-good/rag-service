package com.init.worker.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.reactive.function.client.WebClient;

/**
 * 기본 WebClient Bean.
 *
 * - Qdrant, Storage 등 일반 HTTP 호출에 사용되는 공용 클라이언트.
 * - OpenAI 전용 WebClient(@Qualifier)와는 별도로 사용된다.
 * - 공통 헤더/로깅/타임아웃이 필요하면 여기서 확장 가능.
 */
@Configuration
public class WebClientConfig {
    @Bean
    public WebClient webClient() {
        return WebClient.builder().build();
    }
}
