package com.init.worker.service;

import com.init.worker.dto.AskRequest;
import com.init.worker.dto.CitationDto;
import com.init.worker.dto.QaAskResponse;
import com.init.worker.dto.RetrieveRequest;
import com.init.worker.dto.RetrieveResponse;
import com.init.worker.dto.SaveQaCitationsRequest;
import com.init.worker.rag.PersonalCategoryIds;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

/**
 * 클라이언트 QA E2E — retrieve → AIG /qa → 응답 citations DB 저장.
 */
@Slf4j
@Service
@ConditionalOnProperty(name = "rag.app.role", havingValue = "api")
public class RagQaService {

    private final RagRetrieveService ragRetrieveService;
    private final AiQaClient aiQaClient;
    private final RagQaCitationService ragQaCitationService;
    private final RagCategoryService categoryService;
    private final com.init.worker.config.RagProperties ragProperties;

    public RagQaService(
            RagRetrieveService ragRetrieveService,
            AiQaClient aiQaClient,
            RagQaCitationService ragQaCitationService,
            RagCategoryService categoryService,
            com.init.worker.config.RagProperties ragProperties) {
        this.ragRetrieveService = ragRetrieveService;
        this.aiQaClient = aiQaClient;
        this.ragQaCitationService = ragQaCitationService;
        this.categoryService = categoryService;
        this.ragProperties = ragProperties;
    }

    @Transactional
    public QaAskResponse ask(
            String aiServiceName,
            AskRequest req,
            String userApiKey,
            UUID userNo,
            UUID transactionId) {
        return askDetailed(aiServiceName, req, userApiKey, userNo, transactionId).response();
    }

    /**
     * QA 실행 + retrieve 그래프 진단 정보.
     * 어드민 벡터 vs 그래프 비교용.
     */
    @Transactional
    public QaRunResult askDetailed(
            String aiServiceName,
            AskRequest req,
            String userApiKey,
            UUID userNo,
            UUID transactionId) {
        if (req.categoryId() != null
                && !PersonalCategoryIds.isPersonal(req.categoryId(), configuredPersonalCategoryId())) {
            categoryService.requireUsableCategory(req.categoryId());
        }

        RetrieveRequest retrieveReq = new RetrieveRequest(
                req.content(),
                req.categoryId(),
                req.documentId(),
                req.graphEnabled());
        RetrieveResponse retrieveRes = ragRetrieveService.retrieve(
                aiServiceName, retrieveReq, userApiKey, userNo, transactionId);

        List<CitationDto> retrieveCitations = retrieveRes.citations() != null
                ? retrieveRes.citations()
                : List.of();

        QaAskResponse aiRes = aiQaClient.qa(
                aiServiceName, req, retrieveCitations, userApiKey, userNo, transactionId);

        List<CitationDto> finalCitations = aiRes.citations() != null ? aiRes.citations() : List.of();
        if (!finalCitations.isEmpty()) {
            ragQaCitationService.saveCitations(
                    new SaveQaCitationsRequest(aiRes.messageId(), finalCitations),
                    transactionId);
        }

        log.info(
                "[RAG][{}][QA] done messageId={} model={} citations={} answerLen={} graphApplied={}",
                transactionId,
                aiRes.messageId(),
                aiRes.modelName(),
                finalCitations.size(),
                aiRes.answer().length(),
                retrieveRes.graphApplied());

        return new QaRunResult(
                aiRes,
                retrieveRes.graphApplied(),
                retrieveRes.graphChunkCount(),
                retrieveRes.graphOnlyPromotedCount());
    }

    public record QaRunResult(
            QaAskResponse response,
            boolean graphApplied,
            int graphChunkCount,
            int graphOnlyPromotedCount
    ) {}

    private String configuredPersonalCategoryId() {
        if (ragProperties.categories() == null
                || ragProperties.categories().personalCategoryId() == null
                || ragProperties.categories().personalCategoryId().isBlank()) {
            return null;
        }
        return ragProperties.categories().personalCategoryId().trim();
    }
}
