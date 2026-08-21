package com.ragservice.worker.controller;

import com.ragservice.worker.dto.admin.GraphCategoryCoverageResponse;
import com.ragservice.worker.dto.admin.GraphDocumentViewResponse;
import com.ragservice.worker.dto.admin.GraphQaCompareRequest;
import com.ragservice.worker.dto.admin.GraphQaCompareResponse;
import com.ragservice.worker.dto.admin.GraphTraverseDebugRequest;
import com.ragservice.worker.dto.admin.GraphTraverseDebugResponse;
import com.ragservice.worker.dto.admin.GraphVocabularyActiveRequest;
import com.ragservice.worker.dto.admin.GraphVocabularyAddRequest;
import com.ragservice.worker.dto.admin.GraphVocabularyResponse;
import com.ragservice.worker.error.code.ErrorCodes;
import com.ragservice.worker.error.exception.AppException;
import com.ragservice.worker.service.RagGraphAdminService;
import com.ragservice.worker.service.RagGraphVocabularyService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * 어드민 임시 테스트용 Graph RAG API.
 * QA 비교(벡터 vs 그래프)와 엔티티/traverse 디버그를 제공한다.
 */
@Slf4j
@RestController
@RequiredArgsConstructor
@RequestMapping({
        "/api/rag/admin/graph",
        "/api/rag/knowledge/graph"
})
@ConditionalOnProperty(name = "rag.app.role", havingValue = "api")
public class AdminGraphController {

    private static final String API_KEY_HEADER = "X-API-Key";
    private static final String USER_NO_HEADER = "X-User-No";
    private static final String TX_ID_HEADER = "X-Transaction-Id";
    private static final String MDC_TX_ID = "transactionId";

    private final RagGraphAdminService graphAdminService;
    private final RagGraphVocabularyService vocabService;

    /** 벡터-only QA vs Graph RAG QA 순차 비교 */
    @PostMapping("/qa-compare")
    public ResponseEntity<GraphQaCompareResponse> qaCompare(
            @Valid @RequestBody GraphQaCompareRequest request,
            @RequestHeader(API_KEY_HEADER) String userApiKey,
            @RequestHeader(USER_NO_HEADER) UUID userNo,
            @RequestHeader(value = TX_ID_HEADER, required = false) String transactionId
    ) {
        UUID txId = resolveTransactionId(transactionId);
        try {
            MDC.put(MDC_TX_ID, txId.toString());
            return ResponseEntity.ok(graphAdminService.compareQa(request, userApiKey, userNo, txId));
        } finally {
            MDC.remove(MDC_TX_ID);
        }
    }

    /** 문서별 그래프 스냅샷(엔티티·관계·청크 브리지) */
    @GetMapping("/documents/{documentId}")
    public ResponseEntity<GraphDocumentViewResponse> viewDocument(
            @PathVariable UUID documentId
    ) {
        return ResponseEntity.ok(graphAdminService.viewDocumentGraph(documentId));
    }

    /** 질문 텍스트 기준 seed→1-hop→청크 가중 traverse 디버그 */
    @PostMapping("/traverse-debug")
    public ResponseEntity<GraphTraverseDebugResponse> traverseDebug(
            @Valid @RequestBody GraphTraverseDebugRequest request
    ) {
        return ResponseEntity.ok(graphAdminService.traverseDebug(request));
    }

    /** 카테고리별 그래프 적재 커버리지 (entities/relations/bridges) */
    @GetMapping("/coverage")
    public ResponseEntity<GraphCategoryCoverageResponse> categoryCoverage(
            @RequestParam("categoryId") UUID categoryId
    ) {
        return ResponseEntity.ok(graphAdminService.categoryCoverage(categoryId));
    }

    /** 현재 TYPE/RELATION 어휘 세트 조회 */
    @GetMapping("/vocabulary")
    public ResponseEntity<GraphVocabularyResponse> vocabulary() {
        return ResponseEntity.ok(vocabService.vocabulary());
    }

    /** TYPE 항목 추가 */
    @PostMapping("/vocabulary/types")
    @ResponseStatus(HttpStatus.CREATED)
    public ResponseEntity<GraphVocabularyResponse> addType(
            @Valid @RequestBody GraphVocabularyAddRequest request) {
        vocabService.addType(request.name());
        return ResponseEntity.status(HttpStatus.CREATED).body(vocabService.vocabulary());
    }

    /** TYPE 활성/비활성 */
    @PutMapping("/vocabulary/types/{name}")
    public ResponseEntity<GraphVocabularyResponse> updateTypeActive(
            @PathVariable String name,
            @Valid @RequestBody GraphVocabularyActiveRequest request) {
        vocabService.setTypeActive(name, request.active());
        return ResponseEntity.ok(vocabService.vocabulary());
    }

    /** TYPE 항목 soft-delete */
    @DeleteMapping("/vocabulary/types/{name}")
    public ResponseEntity<GraphVocabularyResponse> deleteType(@PathVariable String name) {
        vocabService.deleteType(name);
        return ResponseEntity.ok(vocabService.vocabulary());
    }

    /** RELATION 항목 추가 */
    @PostMapping("/vocabulary/relations")
    @ResponseStatus(HttpStatus.CREATED)
    public ResponseEntity<GraphVocabularyResponse> addRelation(
            @Valid @RequestBody GraphVocabularyAddRequest request) {
        vocabService.addRelation(request.name());
        return ResponseEntity.status(HttpStatus.CREATED).body(vocabService.vocabulary());
    }

    /** RELATION 활성/비활성 */
    @PutMapping("/vocabulary/relations/{name}")
    public ResponseEntity<GraphVocabularyResponse> updateRelationActive(
            @PathVariable String name,
            @Valid @RequestBody GraphVocabularyActiveRequest request) {
        vocabService.setRelationActive(name, request.active());
        return ResponseEntity.ok(vocabService.vocabulary());
    }

    /** RELATION 항목 soft-delete */
    @DeleteMapping("/vocabulary/relations/{name}")
    public ResponseEntity<GraphVocabularyResponse> deleteRelation(@PathVariable String name) {
        vocabService.deleteRelation(name);
        return ResponseEntity.ok(vocabService.vocabulary());
    }

    private static UUID resolveTransactionId(String transactionId) {
        if (transactionId == null || transactionId.isBlank()) {
            return UUID.randomUUID();
        }
        try {
            return UUID.fromString(transactionId.trim());
        } catch (IllegalArgumentException e) {
            throw new AppException(ErrorCodes.Api.BAD_REQUEST_TRANSACTION_ID_REQUIRED);
        }
    }
}
