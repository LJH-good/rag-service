package com.init.worker.dto.admin;

import jakarta.validation.constraints.NotBlank;

import java.util.UUID;

/** 어드민 임시 — 벡터-only vs Graph RAG QA 비교 요청. */
public record GraphQaCompareRequest(
        @NotBlank String aiServiceName,
        @NotBlank String content,
        UUID categoryId,
        String documentId,
        String modelCode
) {}
