package com.init.storage.service;

import com.init.storage.config.StorageProperties;
import com.init.storage.exception.StorageException;
import com.init.storage.service.dto.StoreResult;
import com.init.storage.util.ChecksumUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.io.InputStream;
import java.nio.file.*;
import java.time.Duration;
import java.time.Instant;

@Service
@Slf4j
public class FileStorageService {

    private final StorageProperties props;

    public FileStorageService(StorageProperties props) {
        this.props = props;
        log.info("[STORAGE_FS][BOOT] initialized. rootPath={}", props.rootPath());
    }

    public StoreResult save(String storageKey, InputStream in) {
        Instant started = Instant.now();

        String rootPath = props.rootPath();
        if (rootPath == null || rootPath.isBlank()) {
            log.error("[STORAGE_FS] rootPath not configured.");
            throw new StorageException(HttpStatus.INTERNAL_SERVER_ERROR,
                    "STORAGE_ROOT_NOT_CONFIGURED", "storage.rootPath is not configured");
        }

        if (storageKey == null || storageKey.isBlank()) {
            log.warn("[STORAGE_FS] save rejected: storageKey is blank.");
            throw new StorageException(HttpStatus.BAD_REQUEST,
                    "BAD_REQUEST_STORAGE_KEY_REQUIRED", "storage_key is required");
        }

        Path root = Path.of(rootPath).toAbsolutePath().normalize();
        Path target = root.resolve(storageKey).normalize();

        log.info("[STORAGE_FS] save start. storageKey={}, root={}, target={}",
                storageKey, root, target);

        // path traversal 방지
        if (!target.startsWith(root)) {
            log.warn("[STORAGE_FS] invalid storageKey(path traversal). storageKey={}, target={}", storageKey, target);
            throw new StorageException(HttpStatus.BAD_REQUEST,
                    "BAD_REQUEST_INVALID_STORAGE_KEY", "invalid storage_key");
        }

        try {
            // 1) 디렉터리 생성
            Instant t1 = Instant.now();
            Files.createDirectories(target.getParent());
            log.info("[STORAGE_FS] directories ensured. parent={}, elapsedMs={}",
                    target.getParent(), Duration.between(t1, Instant.now()).toMillis());

            // 2) tmp 파일 경로
            Path tmp = target.resolveSibling(target.getFileName().toString() + ".tmp");
            log.info("[STORAGE_FS] tmp prepared. tmp={}", tmp);

            // 3) streaming write + sha256 계산
            long size;
            String checksum;
            Instant t2 = Instant.now();
            try (var out = Files.newOutputStream(tmp, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)) {
                var result = ChecksumUtil.copyAndSha256(in, out);
                size = result.size();
                checksum = result.sha256();
            }
            log.info("[STORAGE_FS] tmp write done. tmp={}, size={}, elapsedMs={}",
                    tmp, size, Duration.between(t2, Instant.now()).toMillis());

            if (size <= 0) {
                Files.deleteIfExists(tmp);
                log.warn("[STORAGE_FS] empty file. storageKey={}, tmp deleted.", storageKey);
                throw new StorageException(HttpStatus.BAD_REQUEST,
                        "EMPTY_FILE", "file is empty");
            }

            // 4) tmp -> target move (atomic)
            Instant t3 = Instant.now();
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            log.info("[STORAGE_FS] move done. target={}, elapsedMs={}",
                    target, Duration.between(t3, Instant.now()).toMillis());

            long totalMs = Duration.between(started, Instant.now()).toMillis();
            log.info("[STORAGE_FS] save success. storageKey={}, size={}, checksum={}, totalElapsedMs={}",
                    storageKey, size, checksum, totalMs);

            return new StoreResult(storageKey, size, checksum);

        } catch (StorageException e) {
            log.warn("[STORAGE_FS] save failed(StorageException). storageKey={}, code={}, message={}",
                    storageKey, e.getCode(), e.getMessage());
            throw e;

        } catch (Exception e) {
            log.error("[STORAGE_FS] save failed(unexpected). storageKey={}, target={}",
                    storageKey, target, e);
            throw new StorageException(HttpStatus.INTERNAL_SERVER_ERROR,
                    "STORAGE_WRITE_FAILED", "failed to store file", e);
        }
    }

    public Path resolveForRead(String storageKey) {
        String rootPath = props.rootPath();
        if (rootPath == null || rootPath.isBlank()) {
            log.error("[STORAGE_FS] rootPath not configured (resolveForRead).");
            throw new StorageException(HttpStatus.INTERNAL_SERVER_ERROR,
                    "STORAGE_ROOT_NOT_CONFIGURED", "storage.rootPath is not configured");
        }

        if (storageKey == null || storageKey.isBlank()) {
            log.warn("[STORAGE_FS] resolveForRead rejected: storageKey is blank.");
            throw new StorageException(HttpStatus.BAD_REQUEST,
                    "BAD_REQUEST_STORAGE_KEY_REQUIRED", "storage_key is required");
        }

        Path root = Path.of(rootPath).toAbsolutePath().normalize();
        Path target = root.resolve(storageKey).normalize();

        log.info("[STORAGE_FS] resolveForRead start. storageKey={}, target={}", storageKey, target);

        if (!target.startsWith(root)) {
            log.warn("[STORAGE_FS] invalid storageKey(path traversal) on read. storageKey={}, target={}", storageKey, target);
            throw new StorageException(HttpStatus.BAD_REQUEST,
                    "BAD_REQUEST_INVALID_STORAGE_KEY", "invalid storage_key");
        }

        if (!Files.exists(target) || !Files.isRegularFile(target)) {
            log.warn("[STORAGE_FS] file not found. storageKey={}, target={}", storageKey, target);
            throw new StorageException(HttpStatus.NOT_FOUND,
                    "FILE_NOT_FOUND", "file not found");
        }

        log.info("[STORAGE_FS] resolveForRead success. storageKey={}, target={}", storageKey, target);
        return target;
    }
}