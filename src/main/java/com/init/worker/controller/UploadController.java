package com.init.worker.controller;

import com.init.worker.config.RagProperties;
import com.init.worker.dto.UploadConfigResponse;
import com.init.worker.dto.UploadResponse;
import com.init.worker.error.code.ErrorCodes;
import com.init.worker.error.exception.AppException;
import com.init.worker.service.RagIngestService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.Map;
import java.util.UUID;

@Slf4j
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/rag")
@ConditionalOnProperty(name = "rag.app.role", havingValue = "api")
public class UploadController {

    private static final String TX_ID_HEADER = "X-Transaction-Id";
    private static final String USER_NO_HEADER = "X-User-No";
    private static final String MDC_TX_ID = "transactionId";

    private final RagIngestService service;
    private final RagProperties ragProperties;

    /** 업로드 제약(최대 파일 크기 등) 조회 — 프론트 사전 차단(dual-guard)의 임계치 소스. */
    @GetMapping("/upload-config")
    public ResponseEntity<UploadConfigResponse> uploadConfig() {
        RagProperties.Upload upload = ragProperties.upload();
        long maxFileSizeBytes = (upload != null && upload.maxFileSizeBytes() != null) ? upload.maxFileSizeBytes() : 0L;
        Map<String, Long> extensionLimits = (upload != null && upload.extensionLimits() != null)
                ? upload.extensionLimits() : Map.of();
        return ResponseEntity.ok(new UploadConfigResponse(maxFileSizeBytes, extensionLimits));
    }

    @PostMapping(value = "/{aiServiceName}/documents/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<UploadResponse> uploadDocument(
            @PathVariable String aiServiceName,
            @RequestHeader(USER_NO_HEADER) UUID userNo,
            @RequestHeader(value = TX_ID_HEADER, required = false) String transactionId,
            @RequestHeader(value = "Content-Type", required = false) String requestContentType,
            @RequestPart("file") MultipartFile file,
            @RequestPart("categoryId") String categoryId,
            @RequestPart("userType") String userType,
            @RequestPart(value = "title", required = false) String title
    ) {
        if (transactionId == null || transactionId.isBlank()) {
            throw new AppException(ErrorCodes.Api.BAD_REQUEST_TRANSACTION_ID_REQUIRED);
        }

        try {
            MDC.put(MDC_TX_ID, transactionId);

            log.info(
                    "[RAG_INGEST][{}][CTRL] upload request. file={} request_content_type={} part_content_type={}",
                    transactionId,
                    file.getOriginalFilename(),
                    requestContentType,
                    file.getContentType());

            UploadResponse res = service.upload(
                    aiServiceName,
                    file,
                    categoryId,
                    userType,
                    title,
                    userNo,
                    UUID.fromString(transactionId)
            );

            log.info("[RAG][{}][CTRL] upload response. documentId={}, fileId={}, jobId={}, originalFileName={}",
                    transactionId, res.documentId(), res.fileId(), res.jobId(), res.originalFileName());

            return ResponseEntity.ok()
                    .header(TX_ID_HEADER, transactionId)
                    .body(res);
        } finally {
            MDC.remove(MDC_TX_ID);
        }
    }
}
