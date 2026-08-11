package com.init.worker.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 스케줄링 기능 활성화 설정.
 *
 * - @Scheduled가 붙은 메서드들이 동작하도록 스프링 스케줄러를 켠다.
 * - (예: RagReindexScheduler 같은 정기 실행 작업)
 */
@Configuration
@EnableScheduling
public class SchedulingConfig {}
