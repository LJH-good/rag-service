package com.ragservice.storage.security;

import com.ragservice.storage.config.StorageProperties;
import com.ragservice.storage.exception.StorageException;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

@Component
public class ApiKeyGuard {
    private final StorageProperties props;

    public ApiKeyGuard(StorageProperties props) {
        this.props = props;
    }

    public void check(HttpServletRequest request) {
        String required = props.apiKey();
        if (required == null || required.isBlank()) return; // 키 미설정이면 패스 (MVP)

        String got = request.getHeader("X-API-KEY");
        if (got == null || got.isBlank() || !required.equals(got)) {
            throw new StorageException(HttpStatus.UNAUTHORIZED, "UNAUTHORIZED", "invalid api key");
        }
    }
}
