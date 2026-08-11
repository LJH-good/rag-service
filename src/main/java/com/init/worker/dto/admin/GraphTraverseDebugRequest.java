package com.init.worker.dto.admin;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.util.UUID;

/**
 * 어드민 임시 테스트용 — 그래프 traverse 디버그 요청.
 * 실제 QA retrieve 와 동일하게 문서 스코프 안에서 seed→1-hop→청크 환원을 수행한다.
 */
public record GraphTraverseDebugRequest(
        @NotBlank String query,
        @NotNull UUID documentId
) {}
