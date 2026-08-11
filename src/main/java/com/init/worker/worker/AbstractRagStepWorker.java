package com.init.worker.worker;

import com.init.worker.domain.RagDocument;
import com.init.worker.domain.RagDocumentJob;
import com.init.worker.domain.enums.RagJobStatus;
import com.init.worker.domain.enums.RagJobStep;
import com.init.worker.repo.RagDocumentJobRepository;
import com.init.worker.repo.RagDocumentRepository;
import com.init.worker.service.RagDocumentLoader;
import com.init.worker.service.RagJobStateMachine;
import com.init.worker.storage.PathPolicy;
import com.init.worker.storage.StorageClient;
import jakarta.transaction.Transactional;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;

import java.util.Optional;
import java.util.UUID;

@Slf4j
public abstract class AbstractRagStepWorker {

    protected final RagDocumentRepository docRepo;
    protected final RagDocumentJobRepository jobRepo;
    protected final RagDocumentLoader documentLoader;
    protected final PathPolicy pathPolicy;
    protected final StorageClient storageClient;
    protected final String workerId;
    protected final RagJobStateMachine ragJobStateMachine;

    protected AbstractRagStepWorker(
            RagDocumentRepository docRepo,
            RagDocumentJobRepository jobRepo,
            RagDocumentLoader documentLoader,
            PathPolicy pathPolicy,
            StorageClient storageClient,
            RagJobStateMachine ragJobStateMachine,
            String workerId
    ) {
        this.docRepo = docRepo;
        this.jobRepo = jobRepo;
        this.documentLoader = documentLoader;
        this.pathPolicy = pathPolicy;
        this.storageClient = storageClient;
        this.workerId = workerId;
        this.ragJobStateMachine = ragJobStateMachine;
    }

    protected abstract RagJobStep step();

    protected abstract String logPrefix();

    /**
     * 업로드 API에서 받은 클라이언트 {@code X-Transaction-Id}. 비동기 worker·langchain·aigateway 상관용.
     */
    protected String clientTransactionId(RagDocumentJob job) {
        UUID tx = job.getTransactionId();
        return tx != null ? tx.toString() : job.getId().toString();
    }

    @Transactional
    public Optional<RagDocumentJob> pick() {
        // PCC·EXTRACT_ENTITY 는 파이프라인 진입 단계(초기 PENDING 픽업), 그 외는 이전 단계 완료 큐를 픽업.
        var candidates = (step() == RagJobStep.PCC || step() == RagJobStep.EXTRACT_ENTITY)
                ? jobRepo.findNextPendingForUpdate(PageRequest.of(0, 1))
                : jobRepo.findNextStepQueueForUpdate(step().toDbStep(), PageRequest.of(0, 1));

        if (candidates.isEmpty()) {
            return Optional.empty();
        }

        RagDocumentJob job = candidates.get(0);
        job.claim();
        jobRepo.save(job);
        log.info("[RAG_PICK][{}] claimed jobId={} docId={} txId={} workerId={}",
                step(), job.getId(), job.getDocumentId(), clientTransactionId(job), workerId);

        return Optional.of(job);
    }

    protected RagDocument loadDocOrThrow(RagDocumentJob job) {
        return documentLoader.loadWithFile(job.getDocumentId()).doc();
    }

    protected RagDocumentLoader.WithFile loadWithFileOrThrow(RagDocumentJob job) {
        return documentLoader.loadWithFile(job.getDocumentId());
    }

    protected void success(RagDocumentJob job, RagJobStep nextStepOrNull) {
        log.debug("[{}][{}] step success. docId={}, nextStep={}",
                logPrefix(), job.getId(), job.getDocumentId(), nextStepOrNull);
        ragJobStateMachine.onStepSuccess(job, nextStepOrNull);
    }

    protected void failure(RagDocumentJob job,
                           RagDocument doc,
                           String code,
                           String message,
                           Exception e) {
        log.warn("[{}][{}] step failure. docId={}, code={}, message={}",
                logPrefix(), job.getId(), doc.getId(), code, message, e);
        ragJobStateMachine.onStepFailure(job, doc, code, message, e);
    }

    /** doc/file 로드 자체가 실패한 경우 — doc 없이 job만 FAILED 처리한다. */
    protected void failWithoutDoc(RagDocumentJob job, String code, String message, Exception e) {
        log.warn("[{}][{}] step failure (no doc). code={}, message={}", logPrefix(), job.getId(), code, message, e);
        ragJobStateMachine.onStepFailureWithoutDoc(job, code, message, e);
    }

    protected void retryLater(RagDocumentJob job, String code, String message, Exception e) {
        log.warn("[{}][{}] transient failure -> requeue. code={}, message={}",
                logPrefix(), job.getId(), code, message, e);
        ragJobStateMachine.onTransientFailureRequeue(job, code, message, e);
    }
}
