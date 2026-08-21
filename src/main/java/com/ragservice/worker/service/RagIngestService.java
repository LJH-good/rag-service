package com.ragservice.worker.service;

import com.ragservice.worker.config.RagProperties;
import com.ragservice.worker.domain.RagDocument;
import com.ragservice.worker.domain.RagDocumentFile;
import com.ragservice.worker.domain.RagDocumentJob;
import com.ragservice.worker.domain.enums.RagTriggerType;
import com.ragservice.worker.domain.enums.UserType;
import com.ragservice.worker.dto.UploadResponse;
import com.ragservice.worker.error.code.ErrorCodes;
import com.ragservice.worker.error.exception.AppException;
import com.ragservice.worker.repo.RagDocumentFileRepository;
import com.ragservice.worker.repo.RagDocumentJobRepository;
import com.ragservice.worker.repo.RagDocumentRepository;
import com.ragservice.worker.storage.PathPolicy;
import com.ragservice.worker.storage.StorageClient;
import com.ragservice.worker.rag.PersonalCategoryIds;
import com.ragservice.worker.util.ByteFingerprint;
import com.ragservice.worker.util.IdGenerator;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.multipart.MultipartFile;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import java.util.UUID;

/**
 * 문서 업로드(ingest) 처리 서비스.
 *
 * 처리 순서:
 * 1) 입력 검증
 * 2) rag_document_files row 생성 (PENDING)
 * 3) 스토리지 업로드
 * 4) rag_document_files 갱신 (UPLOADED)
 * 5) rag_documents row 생성
 * 6) rag_document_jobs row 생성 (PENDING)
 */
@Service
@Slf4j
@ConditionalOnProperty(name = "rag.app.role", havingValue = "api")
public class RagIngestService {

    private final long maxFileSizeBytes;
    private final Map<String, Long> extensionLimits;

    private final RagDocumentFileRepository fileRepo;
    private final RagDocumentRepository docRepo;
    private final RagDocumentJobRepository jobRepo;
    private final PathPolicy pathPolicy;
    private final StorageClient storageClient;
    private final TransactionTemplate txTemplate;
    private final RagCategoryService categoryService;
    /** 업로드 categoryId 파트가 비었을 때 대체할 개인 카테고리 UUID (rag.categories.personal-category-id) */
    private final String personalCategoryId;

    public RagIngestService(
            RagProperties props,
            RagDocumentFileRepository fileRepo,
            RagDocumentRepository docRepo,
            RagDocumentJobRepository jobRepo,
            PathPolicy pathPolicy,
            StorageClient storageClient,
            TransactionTemplate txTemplate,
            RagCategoryService categoryService) {
        this.fileRepo = fileRepo;
        this.docRepo = docRepo;
        this.jobRepo = jobRepo;
        this.pathPolicy = pathPolicy;
        this.storageClient = storageClient;
        this.txTemplate = txTemplate;
        this.categoryService = categoryService;
        String configured = (props != null && props.categories() != null)
                ? props.categories().personalCategoryId()
                : null;
        this.personalCategoryId = (configured == null || configured.isBlank()) ? null : configured.trim();

        if (props == null || props.upload() == null || props.upload().maxFileSizeBytes() == null) {
            throw new AppException(ErrorCodes.Config.UPLOAD_MAX_FILE_SIZE_REQUIRED);
        }
        this.maxFileSizeBytes = props.upload().maxFileSizeBytes();
        this.extensionLimits = (props.upload().extensionLimits() != null)
                ? props.upload().extensionLimits()
                : Map.of();

        if (props.pcc() == null || !props.pcc().langchainEnabled()) {
            throw new AppException(ErrorCodes.Config.LANGCHAIN_PCC_ENABLED_REQUIRED);
        }
    }

    public UploadResponse upload(
            String aiServiceName,
            MultipartFile file,
            String categoryId,
            String userTypeRaw,
            String title,
            UUID userNo,
            UUID transactionId) {

        if (aiServiceName == null || aiServiceName.isBlank()) {
            throw new AppException(ErrorCodes.Api.BAD_REQUEST_AI_SERVICE_NAME_REQUIRED);
        }

        UserType userType = resolveUserType(userTypeRaw);
        validateRequest(file, userNo, transactionId);

        final String originalFileName = resolveFileName(file);
        final byte[] bytes;
        try {
            bytes = file.getBytes();
        } catch (Exception e) {
            throw new AppException(ErrorCodes.Api.BAD_REQUEST_FILE_READ_FAILED,
                    Map.of("reason", e.getMessage()));
        }

        final long fileSize = bytes.length;
        final String ext = extractExt(originalFileName);
        validateFileSize(ext, fileSize);

        final String checksum = ByteFingerprint.sha256Hex(bytes);
        log.info(
                "[RAG_INGEST][{}] file bytes received. file={} size={} sha256={} head64_hex={} part_content_type={}",
                transactionId,
                originalFileName,
                fileSize,
                checksum,
                ByteFingerprint.headHex(bytes),
                file.getContentType());
        final UUID fileId = IdGenerator.newId();
        final UUID documentId = IdGenerator.newId();
        final String traceId = transactionId != null ? transactionId.toString() : documentId.toString();

        // 빠른 선행 체크 (best-effort — 동시 요청 거의 없는 경우 조기 거부)
        if (jobRepo.existsActiveJobByUserNo(userNo)) {
            throw new AppException(ErrorCodes.Api.CONFLICT_UPLOAD_ALREADY_IN_PROGRESS,
                    Map.of("reason", "해당 사용자의 업로드가 이미 처리 중입니다 (userNo=" + userNo + ")"));
        }

        UUID categoryUuid = resolveUploadCategoryId(categoryId, userType);
        categoryService.requireUsableCategory(categoryUuid);

        // 1) file row 생성 (PENDING)
        RagDocumentFile fileRow = new RagDocumentFile(fileId, title, originalFileName);
        txTemplate.executeWithoutResult(s -> fileRepo.save(fileRow));

        final String pathPrefix = buildPathPrefix(categoryUuid, title, originalFileName);
        final String storageKey;
        try {
            // 2) 스토리지 업로드
            storageKey = pathPolicy.buildStorageKey(pathPrefix, documentId.toString(), originalFileName);
            StorageClient.StoreResult stored = storageClient.upload(storageKey, bytes, originalFileName, traceId);
            log.info(
                    "[RAG_INGEST][{}] storage upload done. storageKey={} size={} sha256={} ingest_sha256_match={}",
                    traceId,
                    storageKey,
                    stored.size(),
                    stored.checksum(),
                    checksum.equals(stored.checksum()));

            // 3) file row UPLOADED 갱신
            txTemplate.executeWithoutResult(s -> {
                fileRow.markUploaded(storageKey, fileSize, checksum);
                fileRepo.save(fileRow);
            });

        } catch (Exception e) {
            txTemplate.executeWithoutResult(s -> {
                fileRow.markFailed();
                fileRepo.save(fileRow);
            });
            log.error("[RAG_INGEST][{}] storage upload failed. fileId={}, reason={}",
                    traceId, fileId, e.getMessage(), e);
            throw new AppException(ErrorCodes.Storage.STORAGE_REQUEST_ERROR,
                    Map.of("reason", e.getMessage()));
        }

        // 4+5) document + job 생성 — 중복 체크를 트랜잭션 안으로 이동해 경합 방지
        RagDocument doc = new RagDocument(documentId, fileId, categoryUuid, userType, userNo);
        UUID jobId = IdGenerator.newId();
        RagDocumentJob job = new RagDocumentJob(jobId, documentId, RagTriggerType.UPLOAD, transactionId);

        boolean[] conflictDetected = {false};
        txTemplate.executeWithoutResult(s -> {
            if (jobRepo.existsActiveJobByUserNo(userNo)) {
                conflictDetected[0] = true;
                s.setRollbackOnly();
                return;
            }
            docRepo.save(doc);
            jobRepo.save(job);
        });

        if (conflictDetected[0]) {
            txTemplate.executeWithoutResult(s -> {
                fileRow.markFailed();
                fileRepo.save(fileRow);
            });
            log.warn("[RAG_INGEST][{}] conflict detected after storage upload — file discarded. fileId={}, userNo={}",
                    traceId, fileId, userNo);
            throw new AppException(ErrorCodes.Api.CONFLICT_UPLOAD_ALREADY_IN_PROGRESS,
                    Map.of("reason", "스토리지 업로드 완료 후 처리 충돌 감지 — 파일이 폐기되었습니다 (fileId=" + fileId + ", userNo=" + userNo + ")"));
        }

        log.info("[RAG_INGEST][{}] upload done. fileId={}, documentId={}, jobId={}, storageKey={}",
                traceId, fileId, documentId, jobId, storageKey);

        return UploadResponse.of(
                documentId.toString(),
                fileId.toString(),
                categoryUuid != null ? categoryUuid.toString() : null,
                originalFileName,
                fileSize,
                jobId.toString(),
                transactionId.toString(),
                doc.getCreatedAt());
    }

    private String buildPathPrefix(UUID categoryUuid, String title, String originalFileName) {
        String categoryName = categoryService.resolveCategoryName(categoryUuid);
        String categoryLabel = (categoryName != null && !categoryName.isBlank())
                ? categoryName
                : (categoryUuid != null ? categoryUuid.toString().substring(0, 8) : null);
        String date = LocalDate.now().format(DateTimeFormatter.ofPattern("yyyyMMdd"));
        String titleForPath = (title != null && !title.isBlank()) ? title : originalFileName;
        return pathPolicy.buildPathPrefix(categoryLabel, date, titleForPath);
    }

    private void validateRequest(MultipartFile file, UUID userNo, UUID transactionId) {
        if (file == null || file.isEmpty()) {
            throw new AppException(ErrorCodes.Api.BAD_REQUEST_FILE_REQUIRED);
        }
        if (userNo == null) {
            throw new AppException(ErrorCodes.Api.BAD_REQUEST_USER_ID_REQUIRED);
        }
        if (transactionId == null) {
            throw new AppException(ErrorCodes.Api.BAD_REQUEST_TRANSACTION_ID_REQUIRED);
        }
    }

    private void validateFileSize(String ext, long fileSize) {
        long limit = extensionLimits.getOrDefault(ext, maxFileSizeBytes);
        if (fileSize > limit) {
            throw new AppException(ErrorCodes.Api.BAD_REQUEST_FILE_SIZE_EXCEEDED,
                    Map.of("ext", ext, "maxFileSizeBytes", limit, "fileSizeBytes", fileSize));
        }
    }

    /**
     * GW scope(COMPANY/USER) 또는 user_type(admin/user) 문자열을 UserType 으로 변환한다.
     */
    private UserType resolveUserType(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new AppException(ErrorCodes.Api.BAD_REQUEST_USER_TYPE_REQUIRED);
        }
        String v = raw.trim().toUpperCase();
        if ("COMPANY".equals(v) || "ADMIN".equals(v)) {
            return UserType.admin;
        }
        if ("USER".equals(v)) {
            return UserType.user;
        }
        try {
            return UserType.valueOf(raw.trim().toLowerCase());
        } catch (IllegalArgumentException e) {
            throw new AppException(ErrorCodes.Api.BAD_REQUEST_DOCUMENT_SCOPE_INVALID,
                    Map.of("scope", raw));
        }
    }

    private String resolveFileName(MultipartFile file) {
        String name = file.getOriginalFilename();
        return (name == null || name.isBlank()) ? "original.bin" : name;
    }

    /**
     * 업로드 categoryId 해석 규칙.
     * - USER(개인): 요청값과 무관하게 개인 고정 카테고리로 강제
     * - ADMIN/COMPANY(사내): 기존 규칙 유지(빈 값이면 개인 고정 카테고리로 대체)
     */
    private UUID resolveUploadCategoryId(String categoryId, UserType userType) {
        String raw = categoryId == null ? "" : categoryId.trim();
        if (userType == UserType.user) {
            String fixedPersonalId = PersonalCategoryIds.requireConfigured(personalCategoryId);
            if (!raw.isEmpty() && !raw.equalsIgnoreCase(fixedPersonalId)) {
                log.info("[RAG_INGEST] user scope category overridden. requested={}, forced={}",
                        raw, fixedPersonalId);
            }
            raw = fixedPersonalId;
        } else if (raw.isEmpty()) {
            raw = PersonalCategoryIds.requireConfigured(personalCategoryId);
            log.info("[RAG_INGEST] empty categoryId — using personal categoryId={}", raw);
        }
        try {
            return UUID.fromString(raw);
        } catch (IllegalArgumentException e) {
            throw new AppException(ErrorCodes.Api.BAD_REQUEST_CATEGORY_ID_REQUIRED);
        }
    }

    private String extractExt(String filename) {
        int idx = filename.lastIndexOf('.');
        if (idx < 0 || idx == filename.length() - 1) return "bin";
        String ext = filename.substring(idx + 1).trim().toLowerCase();
        return ext.isBlank() ? "bin" : ext;
    }
}
