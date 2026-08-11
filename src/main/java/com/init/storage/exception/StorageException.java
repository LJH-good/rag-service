package com.init.storage.exception;

import org.springframework.http.HttpStatus;

public class StorageException extends RuntimeException {
    private final HttpStatus status;
    private final String code;

    public StorageException(HttpStatus status, String code, String message) {
        super(message);
        this.status = status;
        this.code = code;
    }

    public StorageException(HttpStatus status, String code, String message, Throwable cause) {
        super(message, cause);
        this.status = status;
        this.code = code;
    }

    public HttpStatus getStatus() { return status; }
    public String getCode() { return code; }
}