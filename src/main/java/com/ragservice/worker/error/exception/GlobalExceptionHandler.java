package com.ragservice.worker.error.exception;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import org.springframework.http.MediaType;

import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.Map;

/**
 * 전역 예외 핸들러.
 *
 * 목적:
 * - AppException을 잡아서 HTTP 상태 + 표준 JSON 바디(code/message/timestamp)로 변환한다.
 * - 컨트롤러/서비스는 어떤 에러인지만 던지고, 응답 포맷은 여기서 일괄 처리한다.
 */
@Slf4j
@RestControllerAdvice
@RequiredArgsConstructor
public class GlobalExceptionHandler {

    private final ErrorHttpStatusMapper statusMapper;

    /**
     * AppException 처리.
     * - statusMapper로 HTTP 상태를 결정
     * - code: 에러 코드(enum name)
     * - message: 템플릿 렌더링된 메시지
     * - timestamp: 응답 생성 시각(추적/디버깅용)
     */
    @ExceptionHandler(MissingRequestHeaderException.class)
    public ResponseEntity<Map<String, Object>> handleMissingHeader(MissingRequestHeaderException e) {
        Map<String, Object> body = Map.of(
                "code", "BAD_REQUEST_HEADER_REQUIRED",
                "message", e.getMessage(),
                "timestamp", OffsetDateTime.now().toString()
        );
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(body);
    }

    @ExceptionHandler(UpstreamErrorException.class)
    public ResponseEntity<byte[]> handleUpstream(UpstreamErrorException e) {
        log.error("Upstream error: status={} body={}", e.getStatus(), e.getRawBody(), e);
        byte[] body = e.getRawBody() != null ? e.getRawBody().getBytes(StandardCharsets.UTF_8) : new byte[0];
        return ResponseEntity.status(e.getStatus()).contentType(MediaType.APPLICATION_JSON).body(body);
    }

    @ExceptionHandler(AppException.class)
    public ResponseEntity<Map<String, Object>> handle(AppException e) {
        HttpStatus status = statusMapper.map(e.getErrorCode());

        Map<String, Object> body = Map.of(
                "code", e.getCode(),
                "message", e.getMessage(),
                "timestamp", OffsetDateTime.now().toString()
        );

        return ResponseEntity.status(status).body(body);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> handleUnexpected(Exception e) {
        log.error("Unhandled exception", e);
        Map<String, Object> body = Map.of(
                "code", "INTERNAL_ERROR",
                "message", e.getClass().getSimpleName() + ": " + (e.getMessage() != null ? e.getMessage() : ""),
                "timestamp", OffsetDateTime.now().toString()
        );
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(body);
    }
}
