package com.init.worker.controller;

import com.init.worker.dto.admin.DataSourceDetailResponse;
import com.init.worker.dto.admin.DataSourceListItem;
import com.init.worker.dto.admin.DataSourceProcessingDetailResponse;
import com.init.worker.dto.admin.DataSourceUpdateRequest;
import com.init.worker.dto.common.DataSourceSearchField;
import com.init.worker.dto.common.PagedResponse;
import com.init.worker.dto.pipeline.JobStatusResponse;
import com.init.worker.service.RagDocumentQueryService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

/**
 * 어드민에서 사용하는 데이터소스(문서) 관리 API.
 * - 조회/다운로드/임베딩 재처리 기능을 제공한다.
 */
@Slf4j
@RestController
@RequiredArgsConstructor
@RequestMapping({
        "/api/rag/admin/datasources",
        "/api/rag/knowledge/datasources"
})
@ConditionalOnProperty(name = "rag.app.role", havingValue = "api")
public class AdminDataSourceController {

    private static final String TX_ID_HEADER = "X-Transaction-Id";

    private final RagDocumentQueryService queryService;

    /** 카테고리/UserType 필터로 데이터소스 목록 조회 */
    @GetMapping
    public ResponseEntity<PagedResponse<DataSourceListItem>> list(
            @RequestParam(value = "categoryId", required = false) UUID categoryId,
            @RequestParam(value = "uncategorized", required = false) Boolean uncategorized,
            @RequestParam(value = "userType", required = false) String userType,
            @RequestParam(value = "search", required = false) String search,
            @RequestParam(value = "searchField", required = false) String searchField,
            @RequestParam(value = "page", defaultValue = "0") int page,
            @RequestParam(value = "size", defaultValue = "20") int size
    ) {
        com.init.worker.domain.enums.UserType ut = resolveUserType(userType);
        return ResponseEntity.ok(queryService.listForAdmin(
                categoryId,
                uncategorized,
                ut,
                search,
                DataSourceSearchField.fromParam(searchField),
                page,
                size
        ));
    }

    /** 데이터소스 상세 조회 (임베딩 메타, Job 이력 포함) */
    @GetMapping("/{documentId}")
    public ResponseEntity<DataSourceDetailResponse> detail(@PathVariable UUID documentId) {
        return ResponseEntity.ok(queryService.detailForAdmin(documentId));
    }

    /**
     * 데이터소스 처리 상세 조회 (청크/파이프라인 진행/실패 단계 분석).
     * - 운영 화면에서 처리 진단용으로 사용한다.
     */
    @GetMapping("/{documentId}/processing-detail")
    public ResponseEntity<DataSourceProcessingDetailResponse> processingDetail(
            @PathVariable UUID documentId,
            @RequestParam(value = "includeChunkText", defaultValue = "true") boolean includeChunkText,
            @RequestParam(value = "chunkLimit", defaultValue = "50") int chunkLimit,
            @RequestParam(value = "previewChars", defaultValue = "500") int previewChars
    ) {
        return ResponseEntity.ok(queryService.processingDetailForAdmin(
                documentId,
                includeChunkText,
                chunkLimit,
                previewChars
        ));
    }

    /** 원본 파일 다운로드 */
    @GetMapping("/{documentId}/download")
    public ResponseEntity<byte[]> download(
            @PathVariable UUID documentId,
            @RequestHeader(value = TX_ID_HEADER, required = false) String transactionId
    ) {
        String traceId = transactionId != null ? transactionId : documentId.toString();
        DataSourceDetailResponse detail = queryService.detailForAdmin(documentId);
        byte[] bytes = queryService.downloadForAdmin(documentId, traceId);

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

    /** 데이터소스 제목 수정 */
    @PutMapping("/{documentId}")
    public ResponseEntity<DataSourceDetailResponse> update(
            @PathVariable UUID documentId,
            @Valid @RequestBody DataSourceUpdateRequest req
    ) {
        return ResponseEntity.ok(queryService.updateTitleForAdmin(documentId, req.title().trim()));
    }

    /** 데이터소스 삭제 (soft-delete) */
    @DeleteMapping("/{documentId}")
    public ResponseEntity<Void> delete(@PathVariable UUID documentId) {
        queryService.deleteForAdmin(documentId);
        return ResponseEntity.noContent().build();
    }

    /**
     * 재인덱싱 — Pass1(EXTRACT_ENTITY)부터 파이프라인 재기동.
     * graph.enabled consumer 가 필요. 그래프 공백 문서 복구·골든셋 전 재추출에 사용.
     */
    @PostMapping("/{documentId}/reindex")
    public ResponseEntity<JobStatusResponse> reindex(
            @PathVariable UUID documentId,
            @RequestHeader(value = TX_ID_HEADER, required = false) String transactionId
    ) {
        UUID txId = null;
        if (transactionId != null && !transactionId.isBlank()) {
            try {
                txId = UUID.fromString(transactionId.trim());
            } catch (IllegalArgumentException ignored) {
                txId = null;
            }
        }
        return ResponseEntity.accepted().body(queryService.reindexForAdmin(documentId, txId));
    }

    private com.init.worker.domain.enums.UserType resolveUserType(String raw) {
        if (raw == null || raw.isBlank()) return null;
        try {
            return com.init.worker.domain.enums.UserType.valueOf(raw.trim().toLowerCase());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
