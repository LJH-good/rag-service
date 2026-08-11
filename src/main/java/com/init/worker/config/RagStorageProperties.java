package com.init.worker.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * RAG 스토리지 설정 프로퍼티.
 */
@Data
@ConfigurationProperties(prefix = "rag.storage")
public class RagStorageProperties {

    /**
     * MinIO 설정
     */
    private Minio minio = new Minio();

    @Data
    public static class Minio {

        /**
         * MinIO 접속 endpoint
         * 예: http://192.168.0.134:9900
         */
        private String endpoint;

        /**
         * MinIO access key
         */
        private String accessKey;

        /**
         * MinIO secret key
         */
        private String secretKey;

        /**
         * 파일을 저장할 bucket 명
         */
        private String bucket;

        /**
         * 외부 공개 URL이 필요한 경우 사용할 수 있는 선택값
         * 현재 업로드/다운로드 로직에서는 필수 아님
         */
        private String publicUrl;
    }
}
