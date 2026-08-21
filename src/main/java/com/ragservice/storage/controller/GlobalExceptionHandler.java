package com.ragservice.storage.controller;

import com.ragservice.storage.exception.ErrorResponse;
import com.ragservice.storage.exception.StorageException;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.time.OffsetDateTime;

@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(StorageException.class)
    public ResponseEntity<ErrorResponse> handle(StorageException e) {
        return ResponseEntity.status(e.getStatus())
                .body(new ErrorResponse(e.getMessage(), e.getCode(), OffsetDateTime.now()));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleUnknown(Exception e) {
        return ResponseEntity.status(500)
                .body(new ErrorResponse("internal error", "INTERNAL_ERROR", OffsetDateTime.now()));
    }
}
