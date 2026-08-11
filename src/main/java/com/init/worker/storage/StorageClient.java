package com.init.worker.storage;

/**
 * 스토리지 접근을 추상화한 인터페이스.
 *
 * - 파일 저장소가 HTTP(storage-service)인지 로컬 FS인지와 무관하게 동일한 업/다운로드 API를 제공한다.
 * - 상위 서비스(ingest/worker/retrieval)는 구현체를 몰라도 storageKey 기반으로 파일을 저장/조회할 수 있다.
 * - StoreResult로 저장 결과(키/크기/체크섬 등)를 표준 형태로 반환한다.
 */
public interface StorageClient {

    /**
     * 업로드 결과 DTO.
     * - storageKey: 저장된 키
     * - size: 저장된 크기
     * - checksum: 무결성 확인 값(필요 시)
     */
    record StoreResult(String storageKey, long size, String checksum) {}

    /**
     * 바이트 배열을 스토리지에 업로드한다.
     *
     * @param storageKey 스토리지 내 저장 키
     * @param bytes 업로드할 파일 바이트
     * @param originalName 원본 파일명
     * @param traceId 추적용 로그 ID
     * @return 저장 결과
     */
    StoreResult upload(String storageKey, byte[] bytes, String originalName, String traceId);

    /**
     * 스토리지에서 파일 바이트를 내려받는다.
     *
     * @param storageKey 스토리지 내 저장 키
     * @param traceId 추적용 로그 ID
     * @return 파일 바이트
     */
    byte[] downloadBytes(String storageKey, String traceId);

    /**
     * 객체가 없으면 null, 그 외 오류는 예외.
     * processing-detail 청크 미리보기 등 "없을 수 있음"이 정상인 조회용.
     */
    default byte[] downloadBytesIfPresent(String storageKey, String traceId) {
        try {
            return downloadBytes(storageKey, traceId);
        } catch (RuntimeException e) {
            String msg = e.getMessage();
            if (msg != null && (msg.contains("does not exist") || msg.contains("NoSuchKey"))) {
                return null;
            }
            throw e;
        }
    }

    /**
     * 원본 객체 GET용 presigned URL (LangChain PCC 등 외부 호출용).
     * 구현체가 지원하지 않으면 {@link UnsupportedOperationException}.
     */
    default String presignedGetUrl(String storageKey, int expirySeconds, String traceId) {
        throw new UnsupportedOperationException("presignedGetUrl not supported for this StorageClient");
    }

    /**
     * 원본 파일 삭제
     * - 업로드 이후 DB 처리 실패 시 보상 처리 용도로 사용할 수 있다.
     */
    void delete(String storageKey, String traceId);
}
