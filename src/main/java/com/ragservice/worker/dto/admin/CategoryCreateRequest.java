package com.ragservice.worker.dto.admin;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record CategoryCreateRequest(
        @NotBlank(message = "카테고리 이름은 필수입니다")
        @Size(max = 100)
        String name,
        @Size(max = 500)
        String description,
        Boolean active
) {}
