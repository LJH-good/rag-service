package com.ragservice.worker.dto.common;

/**
 * 데이터소스(문서) 목록 검색 대상 필드.
 */
public enum DataSourceSearchField {
    /** 제목 + 원본 파일명 + 카테고리명 */
    ALL,
    /** 제목만 */
    TITLE,
    /** 원본 파일명만 */
    ORIGINAL_FILE_NAME,
    /** 카테고리명만 */
    CATEGORY_NAME;

    public static DataSourceSearchField fromParam(String value) {
        if (value == null || value.isBlank()) {
            return ALL;
        }
        try {
            return DataSourceSearchField.valueOf(value.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            return ALL;
        }
    }
}
