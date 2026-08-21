package com.ragservice.worker.dto;

import java.util.Map;

/**
 * 업로드 제약 조회 응답 DTO.
 * 프론트가 업로드 전 사전 차단(dual-guard)에 사용하는 임계치를 노출한다.
 * - maxFileSizeBytes: 확장자 제한이 없을 때의 전체 fallback 한도
 * - extensionLimits: 확장자별 개별 한도 (key: 소문자 확장자, value: bytes)
 */
public record UploadConfigResponse(
        long maxFileSizeBytes,
        Map<String, Long> extensionLimits
) {
}
