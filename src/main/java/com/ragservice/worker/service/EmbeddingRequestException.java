package com.ragservice.worker.service;

/**
 * {@link AiEmbeddingClient} 임베딩 호출 실패를 RagEmbedWorker 가 job.error_code/message 로 옮길 때 사용한다.
 */
public class EmbeddingRequestException extends RuntimeException {

    private final String errorCode;

    public EmbeddingRequestException(String errorCode, String message, Throwable cause) {
        super(message, cause);
        this.errorCode = errorCode;
    }

    public String getErrorCode() {
        return errorCode;
    }
}
