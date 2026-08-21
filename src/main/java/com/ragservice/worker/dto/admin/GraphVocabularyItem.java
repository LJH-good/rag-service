package com.ragservice.worker.dto.admin;

/**
 * 어휘 관리 화면용 항목 (이름·초기 seed·활성).
 */
public record GraphVocabularyItem(
        String name,
        boolean builtin,
        boolean active
) {}
