package com.init.worker.dto.admin;

import jakarta.validation.constraints.NotNull;

public record GraphVocabularyActiveRequest(
        @NotNull Boolean active
) {}
