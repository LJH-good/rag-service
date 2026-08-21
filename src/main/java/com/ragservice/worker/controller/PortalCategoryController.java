package com.ragservice.worker.controller;

import com.ragservice.worker.dto.admin.CategoryResponse;
import com.ragservice.worker.dto.admin.CategorySearchField;
import com.ragservice.worker.service.RagCategoryService;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

/**
 * 포탈 카테고리 조회 API — 활성 카테고리만 반환한다.
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/rag/portal/categories")
@ConditionalOnProperty(name = "rag.app.role", havingValue = "api")
public class PortalCategoryController {

    private static final String USER_NO_HEADER = "X-User-No";

    private final RagCategoryService categoryService;

    @GetMapping
    public ResponseEntity<List<CategoryResponse>> list(
            @RequestHeader(USER_NO_HEADER) UUID userNo,
            @RequestParam(value = "search", required = false) String search,
            @RequestParam(value = "searchField", required = false) String searchField
    ) {
        CategorySearchField field = (search != null && !search.isBlank())
                ? CategorySearchField.fromParam(searchField)
                : CategorySearchField.ALL;
        return ResponseEntity.ok(categoryService.listForPortal(search, field, userNo));
    }
}
