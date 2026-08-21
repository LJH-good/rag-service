package com.ragservice.worker.controller;

import com.ragservice.worker.dto.AskRequest;
import com.ragservice.worker.dto.AskResponse;
import com.ragservice.worker.dto.QaAskResponse;
import com.ragservice.worker.error.code.ErrorCodes;
import com.ragservice.worker.error.exception.AppException;
import com.ragservice.worker.service.RagQaCitationService;
import com.ragservice.worker.service.RagQaService;
import jakarta.validation.Valid;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@Slf4j
@RestController
@RequestMapping("/api/rag")
@ConditionalOnProperty(name = "rag.app.role", havingValue = "api")
public class RagQaController {

    private static final String API_KEY_HEADER = "X-API-Key";
    private static final String TX_ID_HEADER = "X-Transaction-Id";
    private static final String USER_NO_HEADER = "X-User-No";
    private static final String MDC_TX_ID = "transactionId";

    private final RagQaService ragQaService;
    private final RagQaCitationService ragQaCitationService;

    public RagQaController(RagQaService ragQaService, RagQaCitationService ragQaCitationService) {
        this.ragQaService = ragQaService;
        this.ragQaCitationService = ragQaCitationService;
    }

    @PostMapping("/{aiServiceName}/qa")
    public ResponseEntity<QaAskResponse> ask(
            @PathVariable String aiServiceName,
            @Valid @RequestBody AskRequest req,
            @RequestHeader(API_KEY_HEADER) String userApiKey,
            @RequestHeader(USER_NO_HEADER) UUID userNo,
            @RequestHeader(value = TX_ID_HEADER, required = false) String transactionId) {
        if (transactionId == null || transactionId.isBlank()) {
            throw new AppException(ErrorCodes.Api.BAD_REQUEST_TRANSACTION_ID_REQUIRED);
        }

        try {
            MDC.put(MDC_TX_ID, transactionId);

            QaAskResponse res = ragQaService.ask(
                    aiServiceName,
                    req,
                    userApiKey,
                    userNo,
                    UUID.fromString(transactionId));

            log.info(
                    "[RAG][{}][CTRL] qa response. aiServiceName={}, messageId={}, model={}, citations={}, answerLen={}",
                    transactionId,
                    aiServiceName,
                    res.messageId(),
                    res.modelName(),
                    res.citations() == null ? 0 : res.citations().size(),
                    res.answer() == null ? 0 : res.answer().length());

            return ResponseEntity.ok()
                    .header(TX_ID_HEADER, transactionId)
                    .body(res);
        } finally {
            MDC.remove(MDC_TX_ID);
        }
    }

    @GetMapping("/{aiServiceName}/qa/messages/{messageId}")
    public ResponseEntity<AskResponse> getByMessageId(
            @PathVariable String aiServiceName,
            @PathVariable UUID messageId,
            @RequestHeader(value = TX_ID_HEADER, required = false) String transactionId) {
        String effectiveTransactionId = resolveTransactionId(transactionId);

        try {
            MDC.put(MDC_TX_ID, effectiveTransactionId);
            AskResponse res = ragQaCitationService.getByMessageId(messageId);

            log.info(
                    "[RAG][{}][CTRL] qa lookup. aiServiceName={}, messageId={}, model={}, citations={}",
                    effectiveTransactionId,
                    aiServiceName,
                    messageId,
                    res.modelName(),
                    res.citations() == null ? 0 : res.citations().size());

            return ResponseEntity.ok()
                    .header(TX_ID_HEADER, effectiveTransactionId)
                    .body(res);
        } finally {
            MDC.remove(MDC_TX_ID);
        }
    }

    private static String resolveTransactionId(String transactionId) {
        if (transactionId == null || transactionId.isBlank()) {
            return UUID.randomUUID().toString();
        }
        return transactionId.trim();
    }
}
