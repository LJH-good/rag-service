package com.ragservice.worker.rag;

import java.util.UUID;

/**
 * 개인 RAG 전용 카테고리 UUID.
 * <p>
 * 클라이언트/GW는 개인 문서·QA 시 이 ID를 {@code categoryId}로 고정 전달한다.
 * 사내 RAG는 그 외 활성 카테고리 UUID를 사용한다.
 */
public final class PersonalCategoryIds {

    /** 로컬 프로필(application-local-*) YAML 기본값 전용. 운영 코드 fallback 으로 쓰지 않는다. */
    public static final String LOCAL_DEV_DEFAULT = "61911ccb-8733-4b2b-9476-25d2347605a9";

    private PersonalCategoryIds() {}

    public static boolean isPersonal(UUID categoryId, String configuredPersonalId) {
        if (categoryId == null) {
            return true;
        }
        return matches(categoryId.toString(), configuredPersonalId);
    }

    public static boolean isPersonal(String categoryId, String configuredPersonalId) {
        if (categoryId == null || categoryId.isBlank()) {
            return true;
        }
        return matches(categoryId, configuredPersonalId);
    }

    /** QA·검색용: 개인이면 null(필터 분기), 사내면 categoryId 문자열 */
    public static String searchCategoryIdOrNull(UUID categoryId, String configuredPersonalId) {
        return isPersonal(categoryId, configuredPersonalId) ? null : categoryId.toString();
    }

    public static String searchCategoryIdOrNull(String categoryId, String configuredPersonalId) {
        return isPersonal(categoryId, configuredPersonalId) ? null : categoryId.trim();
    }

    private static boolean matches(String categoryId, String configuredPersonalId) {
        if (configuredPersonalId == null || configuredPersonalId.isBlank()) {
            return false;
        }
        return categoryId.trim().equalsIgnoreCase(configuredPersonalId.trim());
    }

    public static String requireConfigured(String configuredPersonalId) {
        if (configuredPersonalId == null || configuredPersonalId.isBlank()) {
            throw new IllegalStateException(
                    "rag.categories.personal-category-id (env RAG_PERSONAL_CATEGORY_ID) is required");
        }
        return configuredPersonalId.trim();
    }
}
