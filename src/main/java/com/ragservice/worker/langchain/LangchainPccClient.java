package com.ragservice.worker.langchain;

import com.ragservice.worker.config.RagProperties;
import com.ragservice.worker.error.code.ErrorCodes;
import com.ragservice.worker.error.exception.AppException;
import com.ragservice.worker.rag.PersonalCategoryIds;
import com.ragservice.worker.dto.pcc.PccChunkRequest;
import com.ragservice.worker.dto.pcc.PccIngestRequest;
import com.ragservice.worker.dto.pcc.PccIngestResponse;
import com.ragservice.worker.dto.pcc.PccParseCleanResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import java.util.Map;
import java.util.UUID;

/**
 * LangChain-Service 통합 PCC HTTP API 호출.
 * <p>
 * 계약: {@code POST {baseUrl}{invokePath}} + 바디 {@link PccIngestRequest},
 * 헤더 {@code X-Transaction-Id} (필수), 개인 문서({@code categoryId} 없음) 시 {@code X-User-No} (필수).
 * 경로 기본값
 * {@code /api/internal/rag/pcc/ingest}.
 */
@Slf4j
public class LangchainPccClient {

    private static final String PARSE_CLEAN_PATH = "/api/internal/rag/pcc/parse-clean";
    private static final String CHUNK_PATH = "/api/internal/rag/pcc/chunk";

    private final WebClient webClient;
    private final RagProperties props;

    public LangchainPccClient(WebClient pccLangchainWebClient, RagProperties props) {
        this.webClient = pccLangchainWebClient;
        this.props = props;
    }

    /**
     * @param transactionId 업로드 시 클라이언트 {@code X-Transaction-Id} (job.transaction_id)
     */
    public PccIngestResponse invoke(PccIngestRequest request, String transactionId, UUID userNo) {
        String path = props.pcc().invokePath();
        if (path == null || path.isBlank()) {
            path = "/api/internal/rag/pcc/ingest";
        }
        if (transactionId == null || transactionId.isBlank()) {
            throw new IllegalArgumentException("transactionId is required for LangChain PCC invoke");
        }
        String txId = transactionId.trim();
        String personalId = props.categories() != null ? props.categories().personalCategoryId() : null;
        boolean personal = PersonalCategoryIds.isPersonal(request.categoryId(), personalId);
        if (personal && userNo == null) {
            throw new IllegalArgumentException("userNo is required for LangChain PCC personal category ingest");
        }
        try {
            var spec = webClient.post()
                    .uri(path)
                    .header("X-Transaction-Id", txId);
            if (userNo != null) {
                spec = spec.header("X-User-No", userNo.toString());
            }
            return spec.contentType(MediaType.APPLICATION_JSON)
                    .accept(MediaType.APPLICATION_JSON)
                    .bodyValue(request)
                    .retrieve()
                    .bodyToMono(PccIngestResponse.class)
                    .block();
        } catch (WebClientResponseException e) {
            log.error("[LANGCHAIN_PCC] HTTP {} {} body={}", e.getStatusCode(), path, e.getResponseBodyAsString());
            if (e.getStatusCode() == HttpStatus.UNSUPPORTED_MEDIA_TYPE) {
                throw new AppException(
                        ErrorCodes.Api.UNSUPPORTED_FILE_FORMAT,
                        Map.of("fileName", request.originalFileName() != null ? request.originalFileName() : "unknown"),
                        e
                );
            }
            throw e;
        }
    }

    /**
     * Graph RAG Pass1 입력용 — 파싱·정제까지만 수행한 정리 전 문서 전체 텍스트를 받는다(청킹 안 함).
     */
    public PccParseCleanResponse parseClean(PccIngestRequest request, String transactionId, UUID userNo) {
        if (transactionId == null || transactionId.isBlank()) {
            throw new IllegalArgumentException("transactionId is required for LangChain parse-clean");
        }
        String txId = transactionId.trim();
        String personalId = props.categories() != null ? props.categories().personalCategoryId() : null;
        boolean personal = PersonalCategoryIds.isPersonal(request.categoryId(), personalId);
        if (personal && userNo == null) {
            throw new IllegalArgumentException("userNo is required for LangChain parse-clean personal category");
        }
        try {
            var spec = webClient.post()
                    .uri(PARSE_CLEAN_PATH)
                    .header("X-Transaction-Id", txId);
            if (userNo != null) {
                spec = spec.header("X-User-No", userNo.toString());
            }
            return spec.contentType(MediaType.APPLICATION_JSON)
                    .accept(MediaType.APPLICATION_JSON)
                    .bodyValue(request)
                    .retrieve()
                    .bodyToMono(PccParseCleanResponse.class)
                    .block();
        } catch (WebClientResponseException e) {
            log.error("[LANGCHAIN_PC] HTTP {} {} body={}", e.getStatusCode(), PARSE_CLEAN_PATH, e.getResponseBodyAsString());
            if (e.getStatusCode() == HttpStatus.UNSUPPORTED_MEDIA_TYPE) {
                throw new AppException(
                        ErrorCodes.Api.UNSUPPORTED_FILE_FORMAT,
                        Map.of("fileName", request.originalFileName() != null ? request.originalFileName() : "unknown"),
                        e
                );
            }
            throw e;
        }
    }

    /**
     * Pass1 정리본(또는 폴백 텍스트)을 청킹만 수행한다.
     */
    public PccIngestResponse chunk(PccChunkRequest request, String transactionId) {
        if (transactionId == null || transactionId.isBlank()) {
            throw new IllegalArgumentException("transactionId is required for LangChain chunk");
        }
        try {
            return webClient.post()
                    .uri(CHUNK_PATH)
                    .header("X-Transaction-Id", transactionId.trim())
                    .contentType(MediaType.APPLICATION_JSON)
                    .accept(MediaType.APPLICATION_JSON)
                    .bodyValue(request)
                    .retrieve()
                    .bodyToMono(PccIngestResponse.class)
                    .block();
        } catch (WebClientResponseException e) {
            log.error("[LANGCHAIN_CHUNK] HTTP {} {} body={}", e.getStatusCode(), CHUNK_PATH, e.getResponseBodyAsString());
            throw e;
        }
    }
}
