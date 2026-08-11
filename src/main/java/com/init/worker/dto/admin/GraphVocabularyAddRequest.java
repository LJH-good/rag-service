package com.init.worker.dto.admin;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record GraphVocabularyAddRequest(
        @NotBlank
        @Size(min = 1, max = 100)
        @Pattern(regexp = "^[A-Za-z0-9_]+$", message = "영문자·숫자·언더스코어만 허용")
        String name
) {}
