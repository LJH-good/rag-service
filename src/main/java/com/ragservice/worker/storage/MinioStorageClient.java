package com.ragservice.worker.storage;

import io.minio.GetPresignedObjectUrlArgs;
import io.minio.RemoveObjectArgs;
import io.minio.http.Method;
import lombok.extern.slf4j.Slf4j;
import com.ragservice.worker.util.ByteFingerprint;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import io.minio.GetObjectArgs;
import io.minio.errors.ErrorResponseException;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.util.concurrent.TimeUnit;

/**
 * MinIO 기반 StorageClient 구현체.
 *
 * 역할
 * - MinIO 버킷에 파일 업로드
 * - MinIO 버킷에서 파일 다운로드
 * - 필요 시 보상 처리용 파일 삭제
 *
 * 특징
 * - storageKey를 MinIO object key로 그대로 사용한다.
 * - checksum은 업로드된 원본 bytes 기준으로 SHA-256 계산한다.
 */
@Slf4j
public class MinioStorageClient implements StorageClient {

    private final MinioClient minioClient;
    private final String bucket;

    /**
     * MinIO 클라이언트 생성자.
     *
     * @param endpoint MinIO endpoint
     * @param accessKey MinIO access key
     * @param secretKey MinIO secret key
     * @param bucket 대상 bucket
     */
    public MinioStorageClient(String endpoint, String accessKey, String secretKey, String bucket) {
        this.minioClient = MinioClient.builder()
                .endpoint(normalizeEndpoint(endpoint))
                .credentials(accessKey, secretKey)
                .build();
        this.bucket = bucket;
    }

    /**
     * 파일을 MinIO에 업로드한다.
     *
     * @param storageKey MinIO object key
     * @param bytes 파일 바이트
     * @param originalName 원본 파일명
     * @param traceId 로그 추적용 ID
     * @return 저장 결과
     */
    @Override
    public StoreResult upload(String storageKey, byte[] bytes, String originalName, String traceId) {
        try {
            String uploadSha256 = ByteFingerprint.sha256Hex(bytes);
            String uploadHead64 = ByteFingerprint.headHex(bytes);
            log.debug(
                    "[RAG][{}][MINIO] upload before. bucket={}, storageKey={}, size={}, sha256={}, head64_hex={}, originalName={}",
                    safeTraceId(traceId),
                    bucket,
                    storageKey,
                    bytes.length,
                    uploadSha256,
                    uploadHead64,
                    originalName);

            ByteArrayInputStream inputStream = new ByteArrayInputStream(bytes);

            minioClient.putObject(
                    PutObjectArgs.builder()
                            .bucket(bucket)
                            .object(storageKey)
                            .stream(inputStream, bytes.length, -1)
                            .contentType(detectContentType(originalName))
                            .build()
            );

            byte[] downloaded = readObjectBytes(storageKey);
            String downloadSha256 = ByteFingerprint.sha256Hex(downloaded);
            String downloadHead64 = ByteFingerprint.headHex(downloaded);
            boolean sha256Match = uploadSha256.equals(downloadSha256);
            log.debug(
                    "[RAG][{}][MINIO] upload after roundtrip. bucket={}, storageKey={}, size={}, sha256={}, head64_hex={}, sha256_match={}",
                    safeTraceId(traceId),
                    bucket,
                    storageKey,
                    downloaded.length,
                    downloadSha256,
                    downloadHead64,
                    sha256Match);
            if (!sha256Match) {
                log.error(
                        "[RAG][{}][MINIO] upload roundtrip checksum mismatch. bucket={}, storageKey={}, upload_sha256={}, download_sha256={}, upload_size={}, download_size={}",
                        safeTraceId(traceId),
                        bucket,
                        storageKey,
                        uploadSha256,
                        downloadSha256,
                        bytes.length,
                        downloaded.length);
            }

            return new StoreResult(storageKey, bytes.length, uploadSha256);
        } catch (Exception e) {
            log.error("[RAG][{}][MINIO] upload failed. bucket={}, storageKey={}, reason={}",
                    safeTraceId(traceId), bucket, storageKey, e.getMessage(), e);

            throw new IllegalStateException(
                    "minio upload failed. bucket=" + bucket + ", storageKey=" + storageKey,
                    e
            );
        }
    }

    /**
     * MinIO에서 파일 바이트를 다운로드한다.
     *
     * @param storageKey MinIO object key
     * @param traceId 로그 추적용 ID
     * @return 파일 바이트
     */
    @Override
    public byte[] downloadBytes(String storageKey, String traceId) {
        try {
            byte[] bytes = readObjectBytes(storageKey);

            log.debug(
                    "[RAG][{}][MINIO] download success. bucket={}, storageKey={}, size={}, sha256={}, head64_hex={}",
                    safeTraceId(traceId),
                    bucket,
                    storageKey,
                    bytes.length,
                    ByteFingerprint.sha256Hex(bytes),
                    ByteFingerprint.headHex(bytes));

            return bytes;
        } catch (Exception e) {
            log.error("[RAG][{}][MINIO] download failed. bucket={}, storageKey={}, reason={}",
                    safeTraceId(traceId), bucket, storageKey, e.getMessage(), e);

            throw new IllegalStateException(
                    "minio download failed. bucket=" + bucket + ", storageKey=" + storageKey,
                    e
            );
        }
    }

    @Override
    public byte[] downloadBytesIfPresent(String storageKey, String traceId) {
        try (InputStream inputStream = minioClient.getObject(
                GetObjectArgs.builder()
                        .bucket(bucket)
                        .object(storageKey)
                        .build()
        )) {
            byte[] bytes = inputStream.readAllBytes();
            log.debug("[RAG][{}][MINIO] download (preview). bucket={}, storageKey={}, size={}",
                    safeTraceId(traceId), bucket, storageKey, bytes.length);
            return bytes;
        } catch (ErrorResponseException e) {
            if (isObjectNotFound(e)) {
                log.debug("[RAG][{}][MINIO] object not found (skipped). bucket={}, storageKey={}",
                        safeTraceId(traceId), bucket, storageKey);
                return null;
            }
            log.error("[RAG][{}][MINIO] download failed. bucket={}, storageKey={}, reason={}",
                    safeTraceId(traceId), bucket, storageKey, e.getMessage(), e);
            throw new IllegalStateException(
                    "minio download failed. bucket=" + bucket + ", storageKey=" + storageKey, e);
        } catch (Exception e) {
            log.error("[RAG][{}][MINIO] download failed. bucket={}, storageKey={}, reason={}",
                    safeTraceId(traceId), bucket, storageKey, e.getMessage(), e);
            throw new IllegalStateException(
                    "minio download failed. bucket=" + bucket + ", storageKey=" + storageKey, e);
        }
    }

    private static boolean isObjectNotFound(ErrorResponseException e) {
        if (e.errorResponse() != null && "NoSuchKey".equals(e.errorResponse().code())) {
            return true;
        }
        String msg = e.getMessage();
        return msg != null && msg.contains("does not exist");
    }

    @Override
    public String presignedGetUrl(String storageKey, int expirySeconds, String traceId) {
        int exp = Math.max(60, Math.min(expirySeconds, 604800));
        try {
            String url = minioClient.getPresignedObjectUrl(
                    GetPresignedObjectUrlArgs.builder()
                            .method(Method.GET)
                            .bucket(bucket)
                            .object(storageKey)
                            .expiry(exp, TimeUnit.SECONDS)
                            .build()
            );
            log.debug("[RAG][{}][MINIO] presigned GET. bucket={}, storageKey={}, expirySec={}",
                    safeTraceId(traceId), bucket, storageKey, exp);
            return url;
        } catch (Exception e) {
            log.error("[RAG][{}][MINIO] presigned GET failed. bucket={}, storageKey={}, reason={}",
                    safeTraceId(traceId), bucket, storageKey, e.getMessage(), e);
            throw new IllegalStateException(
                    "minio presigned get failed. bucket=" + bucket + ", storageKey=" + storageKey,
                    e
            );
        }
    }

    /**
     * MinIO에서 파일을 삭제한다.
     *
     * 사용 예
     * - 업로드는 성공했지만 DB 저장이나 Job 생성이 실패했을 때
     * - 보상 처리(compensation)로 storage 파일을 정리할 때 사용한다.
     *
     * @param storageKey MinIO object key
     * @param traceId 로그 추적용 ID
     */
    @Override
    public void delete(String storageKey, String traceId) {
        try {
            minioClient.removeObject(
                    RemoveObjectArgs.builder()
                            .bucket(bucket)
                            .object(storageKey)
                            .build()
            );

            log.debug("[RAG][{}][MINIO] delete success. bucket={}, storageKey={}",
                    safeTraceId(traceId), bucket, storageKey);
        } catch (Exception e) {
            log.error("[RAG][{}][MINIO] delete failed. bucket={}, storageKey={}, reason={}",
                    safeTraceId(traceId), bucket, storageKey, e.getMessage(), e);

            throw new IllegalStateException(
                    "minio delete failed. bucket=" + bucket + ", storageKey=" + storageKey,
                    e
            );
        }
    }

    private byte[] readObjectBytes(String storageKey) throws Exception {
        try (InputStream inputStream = minioClient.getObject(
                GetObjectArgs.builder()
                        .bucket(bucket)
                        .object(storageKey)
                        .build()
        )) {
            return inputStream.readAllBytes();
        }
    }

    /**
     * endpoint 정규화.
     *
     * 처리 내용
     * - 앞뒤 공백 제거
     * - http/https 스킴이 없으면 http 기본값 부여
     * - 마지막 / 제거
     */
    private static String normalizeEndpoint(String endpoint) {
        String value = endpoint.trim();

        if (!value.startsWith("http://") && !value.startsWith("https://")) {
            value = "http://" + value;
        }

        if (value.endsWith("/")) {
            value = value.substring(0, value.length() - 1);
        }

        return value;
    }

    /**
     * 파일명 기반 content-type 추정.
     *
     * 주의
     * - 단순 확장자 기반 추정이다.
     * - 필요 시 Tika 같은 라이브러리로 고도화 가능하다.
     */
    private static String detectContentType(String originalName) {
        if (originalName == null || originalName.isBlank()) {
            return "application/octet-stream";
        }

        String lower = originalName.toLowerCase();

        if (lower.endsWith(".pdf")) {
            return "application/pdf";
        }
        if (lower.endsWith(".txt")) {
            return "text/plain";
        }
        if (lower.endsWith(".json")) {
            return "application/json";
        }
        if (lower.endsWith(".csv")) {
            return "text/csv";
        }
        if (lower.endsWith(".png")) {
            return "image/png";
        }
        if (lower.endsWith(".jpg") || lower.endsWith(".jpeg")) {
            return "image/jpeg";
        }
        if (lower.endsWith(".doc")) {
            return "application/msword";
        }
        if (lower.endsWith(".docx")) {
            return "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
        }
        if (lower.endsWith(".ppt")) {
            return "application/vnd.ms-powerpoint";
        }
        if (lower.endsWith(".pptx")) {
            return "application/vnd.openxmlformats-officedocument.presentationml.presentation";
        }
        if (lower.endsWith(".xls")) {
            return "application/vnd.ms-excel";
        }
        if (lower.endsWith(".xlsx")) {
            return "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
        }

        return "application/octet-stream";
    }

    /**
     * traceId가 비어 있을 때 로그용 기본값 처리.
     */
    private static String safeTraceId(String traceId) {
        return (traceId == null || traceId.isBlank()) ? "-" : traceId;
    }
}