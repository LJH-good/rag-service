package com.ragservice.storage.exception;

import java.time.OffsetDateTime;

public record ErrorResponse(
        String message,
        String code,
        OffsetDateTime timestamp
) {}