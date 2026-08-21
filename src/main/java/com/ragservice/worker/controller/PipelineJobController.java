package com.ragservice.worker.controller;

import com.ragservice.worker.dto.common.PagedResponse;
import com.ragservice.worker.dto.pipeline.JobDetailResponse;
import com.ragservice.worker.dto.pipeline.JobStatusResponse;
import com.ragservice.worker.service.PipelineJobQueryService;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

/**
 * 파이프라인 Job 상태 조회 API.
 * - 포탈/어드민 공통으로 사용한다.
 * - 진행 중인 업로드/임베딩 처리 상태를 폴링할 수 있다.
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/rag/pipeline")
@ConditionalOnProperty(name = "rag.app.role", havingValue = "api")
public class PipelineJobController {

    private final PipelineJobQueryService jobQueryService;

    /** Job 상태 단순 조회 (폴링용) */
    @GetMapping("/jobs/{jobId}")
    public ResponseEntity<JobStatusResponse> getJobStatus(@PathVariable UUID jobId) {
        return ResponseEntity.ok(jobQueryService.getJobStatus(jobId));
    }

    /**
     * transactionId 기반 최신 Job 상태 조회.
     * 프론트는 이 응답의 jobId를 고정하여 /jobs/{jobId} 폴링으로 전환할 수 있다.
     */
    @GetMapping("/transactions/{transactionId}/latest-job")
    public ResponseEntity<JobStatusResponse> getLatestJobByTransaction(@PathVariable UUID transactionId) {
        return ResponseEntity.ok(jobQueryService.getLatestJobStatusByTransaction(transactionId));
    }

    /** Job 상세 조회 (임베딩 파트 통계 포함) */
    @GetMapping("/jobs/{jobId}/detail")
    public ResponseEntity<JobDetailResponse> getJobDetail(@PathVariable UUID jobId) {
        return ResponseEntity.ok(jobQueryService.getJobDetail(jobId));
    }

    /** 특정 문서의 Job 이력 목록 (페이징) */
    @GetMapping("/documents/{documentId}/jobs")
    public ResponseEntity<PagedResponse<JobStatusResponse>> listJobsByDocument(
            @PathVariable UUID documentId,
            @RequestParam(value = "page", defaultValue = "0") int page,
            @RequestParam(value = "size", defaultValue = "10") int size
    ) {
        return ResponseEntity.ok(jobQueryService.listJobsByDocument(documentId, page, size));
    }

    /** 특정 문서의 전체 Job 이력 (최신순) */
    @GetMapping("/documents/{documentId}/jobs/all")
    public ResponseEntity<List<JobStatusResponse>> listAllJobsByDocument(@PathVariable UUID documentId) {
        return ResponseEntity.ok(jobQueryService.listAllJobsByDocument(documentId));
    }
}
