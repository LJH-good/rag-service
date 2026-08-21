package com.ragservice.worker.service;

import com.ragservice.worker.domain.RagDocument;
import com.ragservice.worker.domain.RagDocumentFile;
import com.ragservice.worker.domain.RagDocumentJob;
import com.ragservice.worker.domain.RagEmbeddingPart;
import com.ragservice.worker.domain.RagChunk;
import com.ragservice.worker.domain.RagJobStepTiming;
import com.ragservice.worker.domain.enums.ProcessingDisplayStatus;
import com.ragservice.worker.domain.enums.RagEmbeddingPartStatus;
import com.ragservice.worker.domain.enums.RagJobStatus;
import com.ragservice.worker.domain.enums.RagJobStep;
import com.ragservice.worker.domain.enums.RagStepTimingStatus;
import com.ragservice.worker.domain.enums.UserType;
import com.ragservice.worker.domain.enums.RagTriggerType;
import com.ragservice.worker.dto.admin.DataSourceDetailResponse;
import com.ragservice.worker.dto.admin.DataSourceListItem;
import com.ragservice.worker.dto.admin.DataSourceProcessingDetailResponse;
import com.ragservice.worker.dto.common.DataSourceSearchField;
import com.ragservice.worker.dto.common.PagedResponse;
import com.ragservice.worker.dto.pipeline.JobStatusResponse;
import com.ragservice.worker.dto.pipeline.StepTimingEntry;
import com.ragservice.worker.dto.portal.DocumentDetailResponse;
import com.ragservice.worker.dto.portal.DocumentListItem;
import com.ragservice.worker.error.code.ErrorCodes;
import com.ragservice.worker.error.exception.AppException;
import com.ragservice.worker.repo.RagChunkRepository;
import com.ragservice.worker.repo.RagDocumentFileRepository;
import com.ragservice.worker.repo.RagDocumentJobRepository;
import com.ragservice.worker.repo.RagDocumentRepository;
import com.ragservice.worker.repo.RagEmbeddingPartRepository;
import com.ragservice.worker.repo.RagIndexMetadataRepository;
import com.ragservice.worker.repo.RagJobStepTimingRepository;
import com.ragservice.worker.storage.StorageClient;
import com.ragservice.worker.util.IdGenerator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class RagDocumentQueryService {

    private final RagDocumentRepository documentRepo;
    private final RagDocumentFileRepository fileRepo;
    private final RagDocumentJobRepository jobRepo;
    private final RagIndexMetadataRepository indexMetaRepo;
    private final RagChunkRepository chunkRepo;
    private final RagEmbeddingPartRepository embeddingPartRepo;
    private final RagJobStepTimingRepository stepTimingRepo;
    private final StorageClient storageClient;
    private final RagCategoryService categoryService;
    private final RagDocumentLoader documentLoader;
    private final RagDocumentPurgeService purgeService;

    // ─── 포탈용 ───────────────────────────────────────────────

    public PagedResponse<DocumentListItem> listForPortal(
            UUID userNo,
            UUID categoryId,
            String search,
            DataSourceSearchField searchField,
            int page,
            int size
    ) {
        Pageable pageable = PageRequest.of(page, size);
        Page<RagDocument> docs = documentRepo.findByFilter(
                userNo, categoryId, null, search, searchField, true, null, pageable);
        List<DocumentListItem> items = docs.getContent().stream()
                .map(doc -> {
                    RagDocumentFile file = fileRepo.findById(doc.getFileId()).orElseThrow(
                            () -> new AppException(ErrorCodes.Api.DOCUMENT_FILE_NOT_FOUND,
                                    Map.of("documentId", doc.getId())));
                    List<RagDocumentJob> jobs = jobRepo.findByDocumentId(doc.getId());
                    RagDocumentJob latest = jobs.isEmpty() ? null : jobs.get(0);
                    return DocumentListItem.of(doc, file, latest);
                }).toList();
        return PagedResponse.of(items, docs);
    }

    public DocumentDetailResponse detailForPortal(UUID documentId, UUID userNo) {
        RagDocument doc = findActiveDocumentForPortalOrThrow(documentId, userNo);
        RagDocumentFile file = fileRepo.findById(doc.getFileId())
                .orElseThrow(() -> new AppException(ErrorCodes.Api.DOCUMENT_FILE_NOT_FOUND,
                        Map.of("documentId", documentId)));
        List<RagDocumentJob> jobs = jobRepo.findByDocumentId(documentId);
        String categoryName = categoryService.resolveCategoryName(doc.getCategoryId());
        return DocumentDetailResponse.of(doc, file, jobs, categoryName);
    }

    public byte[] downloadForPortal(UUID documentId, UUID userNo, String traceId) {
        RagDocument doc = findActiveDocumentForPortalOrThrow(documentId, userNo);
        RagDocumentFile file = fileRepo.findById(doc.getFileId())
                .orElseThrow(() -> new AppException(ErrorCodes.Api.DOCUMENT_FILE_NOT_FOUND,
                        Map.of("documentId", documentId)));
        if (file.getStorageKey() == null || file.getStorageKey().isBlank()) {
            throw new AppException(ErrorCodes.Api.FILE_STORAGE_KEY_MISSING,
                    Map.of("documentId", documentId));
        }
        return storageClient.downloadBytes(file.getStorageKey(), traceId);
    }

    // ─── 어드민용 ───────────────────────────────────────────────

    public PagedResponse<DataSourceListItem> listForAdmin(
            UUID categoryId,
            Boolean uncategorizedOnly,
            UserType userType,
            String search,
            DataSourceSearchField searchField,
            int page,
            int size
    ) {
        Pageable pageable = PageRequest.of(page, size);
        Page<RagDocument> docs = documentRepo.findByFilter(
                null, categoryId, userType, search, searchField, false, uncategorizedOnly, pageable);
        List<DataSourceListItem> items = docs.getContent().stream()
                .map(doc -> {
                    RagDocumentFile file = fileRepo.findById(doc.getFileId()).orElseThrow(
                            () -> new AppException(ErrorCodes.Api.DOCUMENT_FILE_NOT_FOUND,
                                    Map.of("documentId", doc.getId())));
                    List<RagDocumentJob> jobs = jobRepo.findByDocumentId(doc.getId());
                    RagDocumentJob latest = jobs.isEmpty() ? null : jobs.get(0);
                    String categoryName = categoryService.resolveCategoryName(doc.getCategoryId());
                    return DataSourceListItem.of(doc, file, latest, categoryName);
                }).toList();
        return PagedResponse.of(items, docs);
    }

    public DataSourceDetailResponse detailForAdmin(UUID documentId) {
        RagDocument doc = findActiveDocumentForAdminOrThrow(documentId);
        RagDocumentFile file = fileRepo.findById(doc.getFileId())
                .orElseThrow(() -> new AppException(ErrorCodes.Api.DOCUMENT_FILE_NOT_FOUND,
                        Map.of("documentId", documentId)));
        List<RagDocumentJob> jobs = jobRepo.findByDocumentId(documentId);
        var indexMeta = indexMetaRepo.findByDocumentId(documentId).orElse(null);
        String categoryName = categoryService.resolveCategoryName(doc.getCategoryId());
        return DataSourceDetailResponse.of(doc, file, jobs, indexMeta, categoryName);
    }

    public DataSourceProcessingDetailResponse processingDetailForAdmin(
            UUID documentId,
            boolean includeChunkText,
            int chunkLimit,
            int previewChars
    ) {
        RagDocument doc = findActiveDocumentForAdminOrThrow(documentId);
        return buildProcessingDetail(doc, includeChunkText, chunkLimit, previewChars);
    }

    public DataSourceProcessingDetailResponse processingDetailForPortal(
            UUID documentId,
            UUID userNo,
            boolean includeChunkText,
            int chunkLimit,
            int previewChars
    ) {
        RagDocument doc = findActiveDocumentForPortalOrThrow(documentId, userNo);
        return buildProcessingDetail(doc, includeChunkText, chunkLimit, previewChars);
    }

    private DataSourceProcessingDetailResponse buildProcessingDetail(
            RagDocument doc,
            boolean includeChunkText,
            int chunkLimit,
            int previewChars
    ) {
        UUID documentId = doc.getId();
        RagDocumentFile file = fileRepo.findById(doc.getFileId())
                .orElseThrow(() -> new AppException(ErrorCodes.Api.DOCUMENT_FILE_NOT_FOUND,
                        Map.of("documentId", documentId)));

        int safeChunkLimit = Math.max(1, Math.min(chunkLimit, 200));
        int safePreviewChars = Math.max(100, Math.min(previewChars, 4000));

        List<RagDocumentJob> jobs = jobRepo.findByDocumentId(documentId);
        RagDocumentJob latest = jobs.isEmpty() ? null : jobs.get(0);

        List<RagChunk> chunks = chunkRepo.findPageByDoc(documentId, 0, PageRequest.of(0, safeChunkLimit));
        List<RagEmbeddingPart> parts = embeddingPartRepo.findPartsByDocumentId(documentId);
        Map<UUID, RagEmbeddingPart> partByChunkId = new HashMap<>();
        for (RagEmbeddingPart part : parts) {
            partByChunkId.putIfAbsent(part.getChunkId(), part);
        }

        List<DataSourceProcessingDetailResponse.ChunkInfo> chunkInfos = new ArrayList<>(chunks.size());
        String traceId = latest != null && latest.getTransactionId() != null
                ? latest.getTransactionId().toString()
                : documentId.toString();
        boolean loadChunkPreview = includeChunkText && shouldLoadChunkPreview(latest, chunks);
        for (RagChunk chunk : chunks) {
            RagEmbeddingPart part = partByChunkId.get(chunk.getId());
            String preview = null;
            if (loadChunkPreview) {
                preview = loadChunkTextPreview(file, documentId, chunk, traceId, safePreviewChars);
            }
            String canonicalChunkKey = documentLoader.canonicalChunkStorageKey(
                    file, documentId, chunk.getChunkIndex());
            chunkInfos.add(new DataSourceProcessingDetailResponse.ChunkInfo(
                    chunk.getId().toString(),
                    chunk.getChunkIndex(),
                    chunk.getCharCount(),
                    canonicalChunkKey,
                    part != null ? part.getStatus() : null,
                    part != null ? part.getPointCount() : null,
                    preview
            ));
        }

        long readyParts = parts.stream().filter(p -> p.getStatus() == RagEmbeddingPartStatus.READY).count();
        long upsertedParts = parts.stream().filter(p -> p.getStatus() == RagEmbeddingPartStatus.UPSERTED).count();
        long failedParts = parts.stream().filter(p -> p.getStatus() == RagEmbeddingPartStatus.FAILED).count();
        long totalPoints = parts.stream()
                .map(RagEmbeddingPart::getPointCount)
                .filter(v -> v != null)
                .mapToLong(Long::longValue)
                .sum();

        ProcessingDisplayStatus processingStatus = DocumentProcessingResolver.resolve(file, latest);
        boolean exposeErrors = processingStatus == ProcessingDisplayStatus.FAILED;

        DataSourceProcessingDetailResponse.PipelineDetail pipeline = null;
        if (latest != null) {
            pipeline = new DataSourceProcessingDetailResponse.PipelineDetail(
                    latest.getStatus(),
                    latest.getCurrentStep(),
                    exposeErrors ? inferFailedStep(latest) : null,
                    DocumentProcessingResolver.exposedErrorCode(file, latest),
                    DocumentProcessingResolver.exposedErrorMessage(file, latest),
                    JobStatusResponse.of(latest).progressPercent(),
                    chunkRepo.countByDoc(documentId),
                    parts.size(),
                    readyParts,
                    upsertedParts,
                    exposeErrors ? failedParts : 0,
                    totalPoints
            );
        }

        List<DataSourceProcessingDetailResponse.JobInfo> jobInfos = jobs.stream()
                .map(job -> new DataSourceProcessingDetailResponse.JobInfo(
                        job.getId().toString(),
                        job.getStatus(),
                        job.getCurrentStep(),
                        job.getTriggerType().name(),
                        DocumentProcessingResolver.exposedErrorCode(file, job),
                        DocumentProcessingResolver.exposedErrorMessage(file, job),
                        job.getStartedAt(),
                        job.getEndedAt(),
                        job.getCreatedAt()
                )).toList();

        OffsetDateTime jobCreatedAt = latest != null ? latest.getCreatedAt() : null;
        List<StepTimingEntry> stepTimings = aggregateStepTimings(documentId, jobCreatedAt);

        String categoryName = categoryService.resolveCategoryName(doc.getCategoryId());
        return new DataSourceProcessingDetailResponse(
                doc.getId().toString(),
                file.getId().toString(),
                doc.getCategoryId() != null ? doc.getCategoryId().toString() : null,
                categoryName,
                doc.getUserType(),
                doc.getUserNo().toString(),
                file.getTitle(),
                file.getOriginalFileName(),
                file.getFileSize() != null ? file.getFileSize() : 0L,
                file.getChecksum(),
                file.getStatus(),
                pipeline,
                jobInfos,
                chunkInfos,
                stepTimings,
                doc.getCreatedAt()
        );
    }

    /**
     * 단계별 타이밍 이력을 단계당 대표 1건으로 집계한다.
     * 같은 단계에 여러 시도가 있으면 SUCCEEDED 를 우선하고, 없으면 가장 최근 시작(최신 attempt) 행을 택한다.
     * 반환 순서는 대표 행의 시작 시각 오름차순(파이프라인 진행 순서).
     * waitBeforeMs: 첫 step은 Job 생성 시각 기준, 이후 step은 직전 step 종료 시각 기준으로 계산한다.
     */
    private List<StepTimingEntry> aggregateStepTimings(UUID documentId, OffsetDateTime jobCreatedAt) {
        List<RagJobStepTiming> rows = stepTimingRepo.findByDocumentIdOrderByStartedAtAsc(documentId);
        if (rows.isEmpty()) {
            return List.of();
        }
        Map<RagJobStep, RagJobStepTiming> repByStep = new LinkedHashMap<>();
        for (RagJobStepTiming row : rows) {
            RagJobStepTiming current = repByStep.get(row.getStep());
            if (current == null || preferTiming(current, row)) {
                repByStep.put(row.getStep(), row);
            }
        }
        List<RagJobStepTiming> sorted = repByStep.values().stream()
                .sorted(Comparator.comparing(RagJobStepTiming::getStartedAt))
                .toList();

        List<StepTimingEntry> result = new ArrayList<>(sorted.size());
        OffsetDateTime prevEndedAt = jobCreatedAt;
        for (RagJobStepTiming t : sorted) {
            Long waitBeforeMs = null;
            if (prevEndedAt != null && t.getStartedAt() != null) {
                waitBeforeMs = Math.max(0, Duration.between(prevEndedAt, t.getStartedAt()).toMillis());
            }
            result.add(new StepTimingEntry(
                    t.getStep(),
                    t.getStatus(),
                    t.getAttempt(),
                    t.getDurationMs(),
                    waitBeforeMs,
                    t.getErrorCode(),
                    t.getStartedAt(),
                    t.getEndedAt()
            ));
            if (t.getEndedAt() != null) {
                prevEndedAt = t.getEndedAt();
            }
        }
        return result;
    }

    /**
     * candidate 가 current 보다 대표로 더 적합하면 true.
     * SUCCEEDED 우선 → FAILED(에러 확인용) → 그다음 늦게 시작한 시도.
     */
    private boolean preferTiming(RagJobStepTiming current, RagJobStepTiming candidate) {
        int currentRank = timingStatusRank(current.getStatus());
        int candidateRank = timingStatusRank(candidate.getStatus());
        if (currentRank != candidateRank) {
            return candidateRank > currentRank;
        }
        return candidate.getStartedAt().isAfter(current.getStartedAt());
    }

    private static int timingStatusRank(RagStepTimingStatus status) {
        if (status == null) {
            return 0;
        }
        return switch (status) {
            case SUCCEEDED -> 3;
            case FAILED -> 2;
            case RUNNING, REQUEUED -> 1;
            case ABANDONED -> 0;
        };
    }

    public byte[] downloadForAdmin(UUID documentId, String traceId) {
        RagDocument doc = findActiveDocumentForAdminOrThrow(documentId);
        RagDocumentFile file = fileRepo.findById(doc.getFileId())
                .orElseThrow(() -> new AppException(ErrorCodes.Api.DOCUMENT_FILE_NOT_FOUND,
                        Map.of("documentId", documentId)));
        if (file.getStorageKey() == null || file.getStorageKey().isBlank()) {
            throw new AppException(ErrorCodes.Api.FILE_STORAGE_KEY_MISSING,
                    Map.of("documentId", documentId));
        }
        return storageClient.downloadBytes(file.getStorageKey(), traceId);
    }

    @Transactional
    public void deleteForPortal(UUID documentId, UUID userNo) {
        RagDocument doc = findDocumentOrThrow(documentId, ErrorCodes.Api.DOCUMENT_NOT_FOUND);
        if (!doc.getUserNo().equals(userNo)) {
            throw new AppException(ErrorCodes.Api.DOCUMENT_NOT_FOUND, Map.of("documentId", documentId));
        }
        if (doc.isDeleted()) {
            throw new AppException(ErrorCodes.Api.DATASOURCE_ALREADY_DELETED, Map.of("documentId", documentId));
        }
        doc.softDelete(OffsetDateTime.now());
        documentRepo.save(doc);
        purgeService.purgeIndexedData(documentId);
    }

    @Transactional
    public DataSourceDetailResponse updateTitleForAdmin(UUID documentId, String title) {
        RagDocument doc = findActiveDocumentForAdminOrThrow(documentId);
        RagDocumentFile file = fileRepo.findById(doc.getFileId())
                .orElseThrow(() -> new AppException(ErrorCodes.Api.DOCUMENT_FILE_NOT_FOUND,
                        Map.of("documentId", documentId)));
        file.updateTitle(title);
        fileRepo.save(file);

        List<RagDocumentJob> jobs = jobRepo.findByDocumentId(documentId);
        var indexMeta = indexMetaRepo.findByDocumentId(documentId).orElse(null);
        String categoryName = categoryService.resolveCategoryName(doc.getCategoryId());
        return DataSourceDetailResponse.of(doc, file, jobs, indexMeta, categoryName);
    }

    @Transactional
    public void deleteForAdmin(UUID documentId) {
        RagDocument doc = findDocumentOrThrow(documentId, ErrorCodes.Api.DATASOURCE_NOT_FOUND);
        if (doc.isDeleted()) {
            throw new AppException(ErrorCodes.Api.DATASOURCE_ALREADY_DELETED, Map.of("documentId", documentId));
        }
        doc.softDelete(OffsetDateTime.now());
        documentRepo.save(doc);
        purgeService.purgeIndexedData(documentId);
    }

    /**
     * 기존 문서에 대해 Pass1(EXTRACT_ENTITY)부터 파이프라인을 다시 돌린다.
     * graph.enabled consumer 가 PENDING+step=null job 을 픽업한다.
     */
    @Transactional
    public JobStatusResponse reindexForAdmin(UUID documentId, UUID transactionId) {
        RagDocument doc = findActiveDocumentForAdminOrThrow(documentId);
        if (jobRepo.existsActiveJobByDocumentId(documentId)) {
            throw new AppException(
                    ErrorCodes.Api.REINDEX_JOB_ALREADY_ACTIVE,
                    Map.of("documentId", documentId));
        }
        UUID txId = transactionId != null ? transactionId : IdGenerator.newId();
        UUID jobId = IdGenerator.newId();
        RagDocumentJob job = new RagDocumentJob(jobId, doc.getId(), RagTriggerType.REINDEX, txId);
        jobRepo.save(job);
        log.info("[REINDEX] queued. documentId={} jobId={} txId={}", documentId, jobId, txId);
        return JobStatusResponse.of(job);
    }

    private RagDocument findActiveDocumentForPortalOrThrow(UUID documentId, UUID userNo) {
        RagDocument doc = findDocumentOrThrow(documentId, ErrorCodes.Api.DOCUMENT_NOT_FOUND);
        if (doc.isDeleted() || !doc.getUserNo().equals(userNo)) {
            throw new AppException(ErrorCodes.Api.DOCUMENT_NOT_FOUND, Map.of("documentId", documentId));
        }
        categoryService.requireUsableCategory(doc.getCategoryId());
        return doc;
    }

    private RagDocument findActiveDocumentForAdminOrThrow(UUID documentId) {
        RagDocument doc = findDocumentOrThrow(documentId, ErrorCodes.Api.DATASOURCE_NOT_FOUND);
        if (doc.isDeleted()) {
            throw new AppException(ErrorCodes.Api.DATASOURCE_NOT_FOUND, Map.of("documentId", documentId));
        }
        return doc;
    }

    private RagDocument findDocumentOrThrow(UUID documentId, ErrorCodes.Api notFoundCode) {
        return documentRepo.findById(documentId)
                .orElseThrow(() -> new AppException(notFoundCode, Map.of("documentId", documentId)));
    }

    /**
     * PCC 완료 전(청크 MinIO 미생성) 폴링 시 불필요한 MinIO GET 을 막는다.
     */
    private boolean shouldLoadChunkPreview(RagDocumentJob latest, List<RagChunk> chunks) {
        if (chunks == null || chunks.isEmpty()) {
            return false;
        }
        if (latest == null) {
            return false;
        }
        if (latest.getStatus() == RagJobStatus.SUCCEEDED || latest.getStatus() == RagJobStatus.FAILED) {
            return true;
        }
        RagJobStep step = latest.getCurrentStep();
        return step == RagJobStep.EMBED || step == RagJobStep.UPSERT
                || step == RagJobStep.EXTRACT_RELATION;
    }

    private String loadChunkTextPreview(RagDocumentFile file,
                                        UUID documentId,
                                        RagChunk chunk,
                                        String traceId,
                                        int maxChars) {
        String canonical = documentLoader.canonicalChunkStorageKey(
                file, documentId, chunk.getChunkIndex());
        byte[] bytes = tryDownloadChunk(canonical, traceId);
        if (bytes == null || bytes.length == 0) {
            String dbKey = chunk.getStorageKey();
            if (dbKey != null && !dbKey.isBlank() && !dbKey.equals(canonical)) {
                bytes = tryDownloadChunk(dbKey, traceId);
            }
        }
        if (bytes == null || bytes.length == 0) {
            return null;
        }
        String text = new String(bytes, StandardCharsets.UTF_8);
        return text.length() > maxChars ? text.substring(0, maxChars) : text;
    }

    private byte[] tryDownloadChunk(String storageKey, String traceId) {
        return storageClient.downloadBytesIfPresent(storageKey, traceId);
    }

    private RagJobStep inferFailedStep(RagDocumentJob latest) {
        if (latest.getStatus() != RagJobStatus.FAILED) {
            return null;
        }
        if (latest.getCurrentStep() != null) {
            return latest.getCurrentStep();
        }
        String code = latest.getErrorCode();
        if (code == null || code.isBlank()) {
            return null;
        }
        if (code.startsWith("PCC_")) {
            return RagJobStep.PARSE;
        }
        if (code.startsWith("EMBED_") || code.startsWith("CHUNK_")) {
            return RagJobStep.EMBED;
        }
        if (code.startsWith("UPSERT_") || code.startsWith("QDRANT_")) {
            return RagJobStep.UPSERT;
        }
        return null;
    }
}
