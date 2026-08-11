package com.init.worker.repo;

import com.init.worker.domain.RagCategory;
import com.init.worker.dto.admin.CategorySearchField;

import java.util.List;
import java.util.UUID;

public interface RagCategoryRepositoryCustom {

    /**
     * @param userNo       null이면 전체, 지정 시 해당 사용자 카테고리만
     * @param search       null/blank면 미적용, 부분 일치(대소문자 무시)
     * @param searchField  검색 대상 (ALL / NAME / DESCRIPTION)
     */
    /** 어드민: 삭제되지 않은 카테고리 (active 필터 선택) */
    List<RagCategory> findForAdmin(UUID userNo, String search, CategorySearchField searchField, Boolean active);

    /** 포탈: 삭제되지 않았고 활성인 카테고리만 */
    List<RagCategory> findForPortal(UUID userNo, String search, CategorySearchField searchField);
}
