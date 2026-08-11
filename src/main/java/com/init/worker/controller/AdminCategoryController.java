package com.init.worker.controller;

import com.init.worker.dto.admin.CategoryCreateRequest;
import com.init.worker.dto.admin.CategoryResponse;
import com.init.worker.dto.admin.CategorySearchField;
import com.init.worker.dto.admin.CategoryUpdateRequest;
import com.init.worker.service.RagCategoryService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.net.URI;
import java.util.List;
import java.util.UUID;

/**
 * 어드민에서 사용하는 카테고리 관리 API.
 */
@RestController
@RequiredArgsConstructor
@RequestMapping({"/api/rag/admin/categories", "/api/rag/categories"})
@ConditionalOnProperty(name = "rag.app.role", havingValue = "api")
public class AdminCategoryController {

    private static final String USER_NO_HEADER = "X-User-No";

    private final RagCategoryService categoryService;

    /**
     * 카테고리 목록 조회.
     * @param search      부분 검색어 (선택)
     * @param searchField ALL(기본) | NAME | DESCRIPTION
     * @param userNo      헤더 지정 시 해당 사용자 카테고리만 (선택)
     */
    @GetMapping
    public ResponseEntity<List<CategoryResponse>> list(
            @RequestParam(value = "search", required = false) String search,
            @RequestParam(value = "searchField", required = false) String searchField,
            @RequestParam(value = "active", required = false) Boolean active,
            @RequestHeader(value = USER_NO_HEADER, required = false) UUID userNo
    ) {
        CategorySearchField field = (search != null && !search.isBlank())
                ? CategorySearchField.fromParam(searchField)
                : CategorySearchField.ALL;
        return ResponseEntity.ok(categoryService.listForAdmin(search, field, userNo, active));
    }

    /** 카테고리 생성 */
    @PostMapping
    public ResponseEntity<CategoryResponse> create(
            @RequestHeader(USER_NO_HEADER) UUID userNo,
            @Valid @RequestBody CategoryCreateRequest req
    ) {
        CategoryResponse created = categoryService.create(req, userNo);
        return ResponseEntity.created(URI.create("/api/rag/admin/categories/" + created.categoryId()))
                .body(created);
    }

    /** 카테고리 수정 */
    @PutMapping("/{categoryId}")
    public ResponseEntity<CategoryResponse> update(
            @PathVariable("categoryId") String categoryId,
            @Valid @RequestBody CategoryUpdateRequest req
    ) {
        return ResponseEntity.ok(categoryService.update(categoryId, req));
    }

    /** 카테고리 삭제 (soft-delete) */
    @DeleteMapping("/{categoryId}")
    public ResponseEntity<Void> delete(@PathVariable("categoryId") String categoryId) {
        categoryService.delete(categoryId);
        return ResponseEntity.noContent().build();
    }
}
