package com.init.worker.config;

import com.init.worker.error.code.ErrorCodes;
import com.init.worker.error.exception.AppException;
import com.init.worker.storage.MinioStorageClient;
import com.init.worker.storage.StorageClient;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 스토리지 관련 Bean 설정.
 *
 * 역할
 * - application.yml의 rag.storage.minio.* 설정 바인딩
 * - 필수값 검증
 * - StorageClient 구현체 등록
 */
@Configuration
@EnableConfigurationProperties(RagStorageProperties.class)
public class StorageConfig {

    /**
     * MinIO 기반 StorageClient Bean 등록.
     *
     * @param properties 스토리지 설정값
     * @return StorageClient
     */
    @Bean
    public StorageClient storageClient(RagStorageProperties properties) {
        validateMinioConfig(properties);

        RagStorageProperties.Minio minio = properties.getMinio();

        return new MinioStorageClient(
                minio.getEndpoint(),
                minio.getAccessKey(),
                minio.getSecretKey(),
                minio.getBucket()
        );
    }

    /**
     * MinIO 설정값 검증.
     *
     * 필수값
     * - endpoint
     * - accessKey
     * - secretKey
     * - bucket
     */
    private void validateMinioConfig(RagStorageProperties properties) {
        if (properties == null || properties.getMinio() == null) {
            throw new AppException(ErrorCodes.Config.MINIO_CONFIG_REQUIRED);
        }

        RagStorageProperties.Minio minio = properties.getMinio();

        if (isBlank(minio.getEndpoint())) {
            throw new AppException(ErrorCodes.Config.MINIO_ENDPOINT_REQUIRED);
        }

        if (isBlank(minio.getAccessKey())) {
            throw new AppException(ErrorCodes.Config.MINIO_ACCESS_KEY_REQUIRED);
        }

        if (isBlank(minio.getSecretKey())) {
            throw new AppException(ErrorCodes.Config.MINIO_SECRET_KEY_REQUIRED);
        }

        if (isBlank(minio.getBucket())) {
            throw new AppException(ErrorCodes.Config.MINIO_BUCKET_REQUIRED);
        }
    }

    /**
     * 공백 문자열 체크 유틸.
     */
    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
