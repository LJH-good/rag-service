package com.init.worker.dto.admin;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record DataSourceUpdateRequest(
        @NotBlank(message = "제목은 필수입니다")
        @Size(max = 200)
        String title
) {}
