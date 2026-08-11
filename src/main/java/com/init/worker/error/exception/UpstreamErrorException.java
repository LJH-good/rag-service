package com.init.worker.error.exception;

public class UpstreamErrorException extends RuntimeException {

    private final int status;
    private final String rawBody;

    public UpstreamErrorException(int status, String rawBody, Throwable cause) {
        super("upstream error: status=" + status, cause);
        this.status = status;
        this.rawBody = rawBody;
    }

    public int getStatus() {
        return status;
    }

    public String getRawBody() {
        return rawBody;
    }
}
