package com.ragservice.worker.worker;

import com.ragservice.worker.config.RagProperties;
import com.ragservice.worker.error.code.ErrorCodes;
import com.ragservice.worker.error.exception.AppException;
import com.ragservice.worker.domain.RagDocument;
import com.ragservice.worker.domain.RagDocumentFile;
import com.ragservice.worker.domain.RagDocumentJob;
import com.ragservice.worker.domain.enums.RagJobStep;
import com.ragservice.worker.dto.pcc.PccChunkHints;
import com.ragservice.worker.dto.pcc.PccIngestRequest;
import com.ragservice.worker.dto.pcc.PccIngestResponse;
import com.ragservice.worker.langchain.LangchainPccClient;
import com.ragservice.worker.repo.RagDocumentJobRepository;
import com.ragservice.worker.repo.RagDocumentRepository;
import com.ragservice.worker.domain.enums.ChunkMode;
import com.ragservice.worker.service.EmbeddingApiKeyResolver;
import com.ragservice.worker.service.RagChunkWriter;
import com.ragservice.worker.service.RagDocumentLoader;
import com.ragservice.worker.service.RagJobStateMachine;
import com.ragservice.worker.service.RagJobStepTimingRecorder;
import com.ragservice.worker.storage.PathPolicy;
import com.ragservice.worker.storage.StorageClient;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

@Service
@Slf4j
@ConditionalOnProperty(prefix = "rag.pcc", name = "langchain-enabled", havingValue = "true")
public class RagPccWorker extends AbstractRagStepWorker {

    private static final String WORKER_ID = "rag-pcc-worker";

    private final RagProperties props;
    private final LangchainPccClient langchainPccClient;
    private final RagChunkWriter chunkWriter;
    private final RagJobStepTimingRecorder stepTimingRecorder;
    private final EmbeddingApiKeyResolver embeddingApiKeyResolver;

    public RagPccWorker(
            RagProperties props,
            RagDocumentRepository docRepo,
            RagDocumentJobRepository jobRepo,
            RagDocumentLoader documentLoader,
            PathPolicy pathPolicy,
            StorageClient storageClient,
            RagJobStateMachine ragJobStateMachine,
            LangchainPccClient langchainPccClient,
            RagChunkWriter chunkWriter,
            RagJobStepTimingRecorder stepTimingRecorder,
            EmbeddingApiKeyResolver embeddingApiKeyResolver
    ) {
        super(docRepo, jobRepo, documentLoader, pathPolicy, storageClient, ragJobStateMachine, WORKER_ID);
        this.props = props;
        this.langchainPccClient = langchainPccClient;
        this.chunkWriter = chunkWriter;
        this.stepTimingRecorder = stepTimingRecorder;
        this.embeddingApiKeyResolver = embeddingApiKeyResolver;
    }

    @Override
    protected RagJobStep step() {
        return RagJobStep.PCC;
    }

    @Override
    protected String logPrefix() {
        return "RAG_PCC";
    }

    /**
     * LangChain PCC·MinIO I/O 동안 DB 커넥션을 붙잡지 않도록 handle 전체 트랜잭션을 두지 않는다.
     * DB 작업은 {@link RagChunkWriter} / {@link RagJobStateMachine}의 짧은 트랜잭션으로 수행한다.
     */
    public void handle(RagDocumentJob job) {
        final String traceId = clientTransactionId(job);
        final RagDocumentLoader.WithFile loaded;
        try {
            loaded = loadWithFileOrThrow(job);
        } catch (Exception e) {
            log.warn("[{}][{}] failed to load doc/file for job", logPrefix(), job.getId(), e);
            failWithoutDoc(job, "LOAD_FAILED", "doc/file load error: " + e.getMessage(), e);
            return;
        }
        RagDocument doc = loaded.doc();
        RagDocumentFile file = loaded.file();
        log.info("[RAG_PCC][{}] handle start. jobId={} docId={} txId={} categoryId={} userNo={}",
                traceId,
                job.getId(),
                doc.getId(),
                job.getTransactionId(),
                doc.getCategoryId(),
                doc.getUserNo());

        try {
            String storageKey = file.getStorageKey();
            if (storageKey == null || storageKey.isBlank()) {
                failure(job, doc, "STORAGE_KEY_EMPTY", "file storageKey is empty", null);
                return;
            }

            int exp = props.pcc().presignExpirySeconds();
            String objectUrl = storageClient.presignedGetUrl(storageKey, exp, traceId);

            RagProperties.Chunk c = props.chunk();
            PccChunkHints hints = new PccChunkHints(c.maxChars(), c.overlapChars(), c.minChars(), c.mode());

            // SEMANTIC 모드이면 문장 임베딩에 동일한 UAK를 사용한다(청크 임베딩과 동일한 키 관리).
            String embeddingApiKey = (c.mode() == ChunkMode.SEMANTIC)
                    ? embeddingApiKeyResolver.resolve(doc)
                    : null;

            String fileName = file.getOriginalFileName() == null ? "original" : file.getOriginalFileName();
            String mime = guessMimeType(fileName);

            PccIngestRequest req = new PccIngestRequest(
                    objectUrl,
                    mime,
                    fileName,
                    doc.getId().toString(),
                    doc.getCategoryId() != null ? doc.getCategoryId().toString() : null,
                    job.getId().toString(),
                    hints,
                    embeddingApiKey
            );

            chunkWriter.clearPriorArtifacts(doc.getId(), traceId, logPrefix());

            PccIngestResponse resp = langchainPccClient.invoke(req, traceId, doc.getUserNo());
            if (resp == null || resp.chunks() == null || resp.chunks().isEmpty()) {
                failure(job, doc, "PCC_EMPTY", "langchain PCC returned no chunks", null);
                return;
            }

            int stored = chunkWriter.store(doc, file, resp.chunks(), traceId);
            if (stored == 0) {
                failure(job, doc, "PCC_NO_TEXT", "all chunk texts were blank", null);
                return;
            }

            // PARSE/CLEAN/CHUNK 단계별 소요 시간 확정(이후 SM end 는 열린 행이 없어 no-op)
            stepTimingRecorder.completeParseCleanChunk(job.getId(), doc.getId(), resp.timings());
            success(job, RagJobStep.EMBED);

            log.info("[{}][{}] PCC ok. chunkCount={}", logPrefix(), traceId, stored);

        } catch (AppException e) {
            if (e.getErrorCode() == ErrorCodes.Api.UNSUPPORTED_FILE_FORMAT) {
                log.warn("[{}][{}] unsupported file format, permanent failure", logPrefix(), traceId, e);
                failure(job, doc, e.getCode(), e.getMessage(), e);
            } else {
                log.warn("[{}][{}] exception", logPrefix(), traceId, e);
                retryLater(job, "PCC_EXCEPTION", "pcc exception: " + e.getMessage(), e);
            }
        } catch (Exception e) {
            log.warn("[{}][{}] exception", logPrefix(), traceId, e);
            retryLater(job, "PCC_EXCEPTION", "pcc exception: " + e.getMessage(), e);
        }
    }

    private static String guessMimeType(String fileName) {
        if (fileName == null) {
            return "application/octet-stream";
        }
        String lower = fileName.toLowerCase();
        if (lower.endsWith(".pdf")) return "application/pdf";
        if (lower.endsWith(".txt")) return "text/plain";
        if (lower.endsWith(".md")) return "text/markdown";
        if (lower.endsWith(".html") || lower.endsWith(".htm")) return "text/html";
        if (lower.endsWith(".json")) return "application/json";
        if (lower.endsWith(".docx")) return "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
        if (lower.endsWith(".doc")) return "application/msword";
        if (lower.endsWith(".xlsx")) return "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
        if (lower.endsWith(".pptx")) return "application/vnd.openxmlformats-officedocument.presentationml.presentation";
        if (lower.endsWith(".hwp")) return "application/x-hwp";
        if (lower.endsWith(".hwpx")) return "application/hwp+zip";
        return "application/octet-stream";
    }
}
