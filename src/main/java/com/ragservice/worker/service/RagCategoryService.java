package com.ragservice.worker.service;

import com.ragservice.worker.domain.RagCategory;
import com.ragservice.worker.dto.admin.CategoryCreateRequest;
import com.ragservice.worker.dto.admin.CategoryResponse;
import com.ragservice.worker.dto.admin.CategorySearchField;
import com.ragservice.worker.dto.admin.CategoryUpdateRequest;
import com.ragservice.worker.error.code.ErrorCodes;
import com.ragservice.worker.error.exception.AppException;
import com.ragservice.worker.repo.RagCategoryRepository;
import com.ragservice.worker.util.IdGenerator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class RagCategoryService {

    private final RagCategoryRepository categoryRepo;

    @Transactional(readOnly = true)
    public List<CategoryResponse> listForAdmin(
            String search,
            CategorySearchField searchField,
            UUID filterUserNo,
            Boolean active
    ) {
        return categoryRepo.findForAdmin(
                filterUserNo,
                normalizeSearch(search),
                searchField != null ? searchField : CategorySearchField.ALL,
                active
        ).stream()
                .map(CategoryResponse::of)
                .toList();
    }

    @Transactional(readOnly = true)
    public List<CategoryResponse> listForPortal(
            String search,
            CategorySearchField searchField,
            UUID filterUserNo
    ) {
        return categoryRepo.findForPortal(
                filterUserNo,
                normalizeSearch(search),
                searchField != null ? searchField : CategorySearchField.ALL
        ).stream()
                .map(CategoryResponse::of)
                .toList();
    }

    @Transactional
    public CategoryResponse create(CategoryCreateRequest req, UUID userNo) {
        UUID id = IdGenerator.newId();
        RagCategory category = new RagCategory(id, req.name(), req.description(), userNo);
        if (req.active() != null) {
            category.update(null, null, req.active());
        }
        categoryRepo.save(category);
        log.info("[CATEGORY] created. id={}, name={}, active={}, userNo={}",
                id, req.name(), category.isActive(), userNo);
        return CategoryResponse.of(category);
    }

    @Transactional
    public CategoryResponse update(String categoryId, CategoryUpdateRequest req) {
        RagCategory category = findActiveOrThrow(categoryId);
        category.update(req.name(), req.description(), req.active());
        categoryRepo.save(category);
        log.info("[CATEGORY] updated. id={}, name={}, active={}", categoryId, req.name(), category.isActive());
        return CategoryResponse.of(category);
    }

    @Transactional
    public void delete(String categoryId) {
        RagCategory category = findActiveOrThrow(categoryId);
        category.softDelete(OffsetDateTime.now());
        categoryRepo.save(category);
        log.info("[CATEGORY] deleted. id={}", categoryId);
    }

    @Transactional(readOnly = true)
    public String resolveCategoryName(UUID categoryId) {
        if (categoryId == null) {
            return null;
        }
        return categoryRepo.findById(categoryId)
                .map(RagCategory::getName)
                .orElse(null);
    }

    /**
     * 업로드·포탈 조회 전 카테고리가 사용 가능한지 검증한다.
     */
    @Transactional(readOnly = true)
    public void requireUsableCategory(UUID categoryId) {
        if (categoryId == null) {
            return;
        }
        RagCategory category = categoryRepo.findById(categoryId)
                .orElseThrow(() -> new AppException(ErrorCodes.Api.CATEGORY_NOT_FOUND,
                        Map.of("categoryId", categoryId)));
        if (category.isDeleted()) {
            throw new AppException(ErrorCodes.Api.CATEGORY_NOT_FOUND,
                    Map.of("categoryId", categoryId));
        }
        if (!category.isActive()) {
            throw new AppException(ErrorCodes.Api.CATEGORY_INACTIVE,
                    Map.of("categoryId", categoryId));
        }
    }

    private RagCategory findActiveOrThrow(String categoryId) {
        UUID id = parseCategoryId(categoryId);
        RagCategory category = categoryRepo.findById(id)
                .orElseThrow(() -> new AppException(ErrorCodes.Api.CATEGORY_NOT_FOUND,
                        Map.of("categoryId", categoryId)));
        if (category.isDeleted()) {
            throw new AppException(ErrorCodes.Api.CATEGORY_ALREADY_DELETED,
                    Map.of("categoryId", categoryId));
        }
        return category;
    }

    private static UUID parseCategoryId(String categoryId) {
        try {
            return UUID.fromString(categoryId);
        } catch (IllegalArgumentException e) {
            throw new AppException(ErrorCodes.Api.CATEGORY_NOT_FOUND,
                    Map.of("categoryId", categoryId));
        }
    }

    private static String normalizeSearch(String search) {
        if (search == null) {
            return null;
        }
        String trimmed = search.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
