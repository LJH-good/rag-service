package com.init.worker.dto;

/**
 * 스토리지 업로드 결과.
 *
 * @param storageKey 저장 키
 * @param fileSize 파일 크기(byte)
 * @param checksum SHA-256 체크섬
 */
public record StoreResult(
        String storageKey,
        long fileSize,
        String checksum
) {
}
