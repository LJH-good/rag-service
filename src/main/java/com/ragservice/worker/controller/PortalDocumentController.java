package com.ragservice.worker.controller;

import com.ragservice.worker.dto.admin.CategoryResponse;
import com.ragservice.worker.dto.admin.CategorySearchField;
import com.ragservice.worker.dto.common.PagedResponse;
import com.ragservice.worker.dto.common.DataSourceSearchField;
import com.ragservice.worker.dto.admin.DataSourceProcessingDetailResponse;
import com.ragservice.worker.dto.portal.DocumentDetailResponse;
import com.ragservice.worker.dto.portal.DocumentListItem;
import com.ragservice.worker.service.RagCategoryService;
import com.ragservice.worker.service.RagDocumentQueryService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;

/**
 * 포탈에서 사용하는 문서 조회/다운로드 API.
 * - X-User-No 헤더로 사용자를 식별한다.
 */
@Slf4j
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/rag/portal/documents")
@ConditionalOnProperty(name = "rag.app.role", havingValue = "api")
public class PortalDocumentController {

    private static final String TX_ID_HEADER = "X-Transaction-Id";
    private static final String USER_NO_HEADER = "X-User-No";

    private final RagDocumentQueryService queryService;
    private final RagCategoryService categoryService;

    /** 포탈 문서 카테고리 목록 — /{documentId} 보다 먼저 등록되어야 라우팅이 정상 동작한다 */
    @GetMapping("/categories")
    public ResponseEntity<List<CategoryResponse>> listCategories(
            @RequestHeader(USER_NO_HEADER) UUID userNo,
            @RequestParam(value = "search", required = false) String search,
            @RequestParam(value = "searchField", required = false) String searchField
    ) {
        CategorySearchField field = (search != null && !search.isBlank())
                ? CategorySearchField.fromParam(searchField)
                : CategorySearchField.ALL;
        return ResponseEntity.ok(categoryService.listForPortal(search, field, userNo));
    }

    /** 내 문서 목록 조회 */
    @GetMapping
    public ResponseEntity<PagedResponse<DocumentListItem>> list(
            @RequestHeader(USER_NO_HEADER) UUID userNo,
            @RequestParam(value = "categoryId", required = false) UUID categoryId,
            @RequestParam(value = "search", required = false) String search,
            @RequestParam(value = "searchField", required = false) String searchField,
            @RequestParam(value = "page", defaultValue = "0") int page,
            @RequestParam(value = "size", defaultValue = "20") int size
    ) {
        return ResponseEntity.ok(queryService.listForPortal(
                userNo,
                categoryId,
                search,
                DataSourceSearchField.fromParam(searchField),
                page,
                size
        ));
    }

    /** 문서 상세 조회 */
    @GetMapping("/{documentId}")
    public ResponseEntity<DocumentDetailResponse> detail(
            @RequestHeader(USER_NO_HEADER) UUID userNo,
            @PathVariable UUID documentId
    ) {
        return ResponseEntity.ok(queryService.detailForPortal(documentId, userNo));
    }

    /** 내 문서 처리 상세 조회 (청크/진행상태/실패정보) */
    @GetMapping("/{documentId}/processing-detail")
    public ResponseEntity<DataSourceProcessingDetailResponse> processingDetail(
            @RequestHeader(USER_NO_HEADER) UUID userNo,
            @PathVariable UUID documentId,
            @RequestParam(value = "includeChunkText", defaultValue = "true") boolean includeChunkText,
            @RequestParam(value = "chunkLimit", defaultValue = "50") int chunkLimit,
            @RequestParam(value = "previewChars", defaultValue = "500") int previewChars
    ) {
        return ResponseEntity.ok(queryService.processingDetailForPortal(
                documentId,
                userNo,
                includeChunkText,
                chunkLimit,
                previewChars
        ));
    }

    /** 원본 파일 다운로드 */
    @GetMapping("/{documentId}/download")
    public ResponseEntity<byte[]> download(
            @RequestHeader(USER_NO_HEADER) UUID userNo,
            @RequestHeader(value = TX_ID_HEADER, required = false) String transactionId,
            @PathVariable UUID documentId
    ) {
        String traceId = transactionId != null ? transactionId : documentId.toString();
        DocumentDetailResponse detail = queryService.detailForPortal(documentId, userNo);
        byte[] bytes = queryService.downloadForPortal(documentId, userNo, traceId);

        String fileName = detail.originalFileName() != null ? detail.originalFileName() : "download";
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_OCTET_STREAM);
        headers.setContentDisposition(
                ContentDisposition.attachment()
                        .filename(fileName, StandardCharsets.UTF_8)
                        .build());
        headers.setContentLength(bytes.length);

        return ResponseEntity.ok().headers(headers).body(bytes);
    }

    /** 문서 삭제 (soft-delete) */
    @DeleteMapping("/{documentId}")
    public ResponseEntity<Void> delete(
            @RequestHeader(USER_NO_HEADER) UUID userNo,
            @PathVariable UUID documentId
    ) {
        queryService.deleteForPortal(documentId, userNo);
        return ResponseEntity.noContent().build();
    }
}
