package com.ragservice.worker.service;

import com.ragservice.worker.domain.RagQaCitation;
import com.ragservice.worker.dto.AskResponse;
import com.ragservice.worker.dto.CitationDto;
import com.ragservice.worker.dto.SaveQaCitationsRequest;
import com.ragservice.worker.error.code.ErrorCodes;
import com.ragservice.worker.error.exception.AppException;
import com.ragservice.worker.repo.RagQaCitationRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Slf4j
@Service
@ConditionalOnProperty(name = "rag.app.role", havingValue = "api")
public class RagQaCitationService {

    private final RagQaCitationRepository citationRepository;
    private final RagCitationEnricher citationEnricher;

    public RagQaCitationService(
            RagQaCitationRepository citationRepository,
            RagCitationEnricher citationEnricher) {
        this.citationRepository = citationRepository;
        this.citationEnricher = citationEnricher;
    }

    @Transactional
    public AskResponse saveCitations(SaveQaCitationsRequest req, UUID transactionId) {
        UUID messageId = req.messageId();
        List<CitationDto> incoming = req.citations() != null ? req.citations() : List.of();
        List<CitationDto> enriched = citationEnricher.enrich(incoming);

        citationRepository.deleteByMessageId(messageId);

        List<RagQaCitation> rows = new ArrayList<>(enriched.size());
        for (CitationDto c : enriched) {
            rows.add(RagQaCitation.of(
                    messageId,
                    c.chunkId(),
                    c.documentId(),
                    scoreOrZero(c.score()),
                    c.page(),
                    c.slide(),
                    c.sheet(),
                    c.sourceUri(),
                    c.snippet()));
        }
        if (!rows.isEmpty()) {
            citationRepository.saveAll(rows);
        }

        log.info(
                "[RAG][{}][CITATIONS] saved messageId={} count={}",
                transactionId,
                messageId,
                enriched.size());

        return new AskResponse(messageId, enriched, null, null);
    }

    @Transactional(readOnly = true)
    public AskResponse getByMessageId(UUID messageId) {
        List<RagQaCitation> rows = citationRepository.findByMessageIdOrderByScoreDesc(messageId);
        if (rows.isEmpty()) {
            throw new AppException(
                    ErrorCodes.Api.QA_CITATIONS_NOT_FOUND,
                    Map.of("messageId", messageId));
        }

        List<CitationDto> citations = rows.stream().map(this::toDto).toList();
        return new AskResponse(messageId, citations, null, null);
    }

    private static BigDecimal scoreOrZero(BigDecimal score) {
        if (score == null) {
            return BigDecimal.ZERO.setScale(4, RoundingMode.HALF_UP);
        }
        return score.setScale(4, RoundingMode.HALF_UP);
    }

    private CitationDto toDto(RagQaCitation c) {
        return new CitationDto(
                c.getChunkId() == null ? null : c.getChunkId().toString(),
                c.getDocumentId(),
                c.getScore(),
                c.getPage(),
                c.getSlide(),
                c.getSheet(),
                c.getSourceUri(),
                c.getSnippet());
    }
}
