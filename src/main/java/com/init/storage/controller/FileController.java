package com.init.storage.controller;

import com.init.storage.security.ApiKeyGuard;
import com.init.storage.service.FileStorageService;
import com.init.storage.service.dto.StoreResult;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.nio.file.Files;
import java.nio.file.Path;

@RestController
public class FileController {

    private final ApiKeyGuard apiKeyGuard;
    private final FileStorageService storage;

    public FileController(ApiKeyGuard apiKeyGuard, FileStorageService storage) {
        this.apiKeyGuard = apiKeyGuard;
        this.storage = storage;
    }

    // 헬스체크 (선택이지만 추천)
    @GetMapping("/health")
    public ResponseEntity<?> health() {
        return ResponseEntity.ok().body(java.util.Map.of("status", "UP"));
    }

    /**
     * POST /files
     * multipart: storage_key, file
     */
    @PostMapping(value = "/files", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public StoreResult upload(
            HttpServletRequest request,
            @RequestPart("storage_key") String storageKey,
            @RequestPart("file") MultipartFile file
    ) throws Exception {
        apiKeyGuard.check(request);
        return storage.save(storageKey, file.getInputStream());
    }

    /**
     * GET /files?storage_key=...
     */
    @GetMapping("/files")
    public ResponseEntity<Resource> download(
            HttpServletRequest request,
            @RequestParam("storage_key") String storageKey
    ) throws Exception {
        apiKeyGuard.check(request);

        Path target = storage.resolveForRead(storageKey);
        Resource resource = new FileSystemResource(target);

        // MVP: octet-stream
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .contentLength(Files.size(target))
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "inline; filename=\"" + target.getFileName().toString() + "\"")
                .body(resource);
    }
}
