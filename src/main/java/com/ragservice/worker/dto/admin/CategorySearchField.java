package com.ragservice.worker.dto.admin;

/**
 * 카테고리 목록 검색 대상.
 */
public enum CategorySearchField {
    /** 카테고리명 + 설명 */
    ALL,
    /** 카테고리명만 */
    NAME,
    /** 설명만 */
    DESCRIPTION;

    public static CategorySearchField fromParam(String value) {
        if (value == null || value.isBlank()) {
            return ALL;
        }
        try {
            return CategorySearchField.valueOf(value.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            return ALL;
        }
    }
}
