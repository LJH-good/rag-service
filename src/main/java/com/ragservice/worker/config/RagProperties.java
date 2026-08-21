package com.ragservice.worker.config;

import com.ragservice.worker.domain.enums.ChunkMode;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;
import java.util.Map;

/**
 * rag.* 설정을 바인딩하는 프로퍼티 모델.
 *
 * - application.yml/properties의 "rag" prefix 하위 값을 한 번에 묶어서 관리한다.
 * - 워커/스토리지/청킹/임베딩/Qdrant 등 RAG 파이프라인 전반 설정을 여기서 주입받는다.
 *
 * 예) rag.env, rag.chunk.maxChars ...
 */
@ConfigurationProperties(prefix = "rag")
public record RagProperties(
        String env,            // 실행 환경 식별(dev/stg/prd 등)
        App app,
        Storage storage,       // OpenStorage 연동 및 key prefix 설정
        Categories categories, // 허용 카테고리 목록(업로드/처리 제한 등)
        Chunk chunk,           // 청킹 파라미터
        Embedding embedding,
        Qdrant qdrant,         // Qdrant 연동 설정
        Worker worker,          // 워커 동작(딜레이/재시도/정책 등)
        Upload upload,
        LangchainService langchainService, // 별도 langchain-service 레포 HTTP 연동 (QA 등)
        Qa qa, // langchain-service QA 위임 경로
        Pcc pcc, // LangChain 통합 PCC (presigned URL 단일 호출)
        Gateway gateway, // Control Plane Gateway (EMBED 경유)
        Purge purge, // soft-delete 문서 물리 데이터 제거
        Graph graph // Graph RAG Pass2(관계 추출) 설정
) {
    public RagProperties {
        if (langchainService == null) {
            langchainService = new LangchainService(false, "", 30_000L, 10 * 1024 * 1024);
        }
        if (qa == null) {
            qa = new Qa("/api/rag/{aiServiceName}/qa");
        }
        if (pcc == null) {
            pcc = new Pcc(false, "", "/api/internal/rag/pcc/ingest", 3600, 120_000L, 32 * 1024 * 1024);
        }
        if (gateway == null) {
            gateway = new Gateway("", 120, 0, 1000, 60_000, 10 * 1024 * 1024);
        }
        if (purge == null) {
            purge = new Purge(false, 100);
        }
        if (graph == null) {
            // timeout/chunkBatch: 2026-07-23 실측 확정값 (900s / batch=8)
            graph = new Graph(false, "openai", "claude", 1_048_576L, null, null, 900, 8, 4000, 604_800L);
        }
        if (chunk == null) {
            chunk = new Chunk(1200, 120, 40, ChunkMode.FIXED);
        } else {
            ChunkMode mode = chunk.mode() != null ? chunk.mode() : ChunkMode.FIXED;
            // SEMANTIC 모드는 max-chars가 임베딩 모델 토큰 한도 근처의 하드 캡이어야 한다.
            // 기본값을 모드별로 구분하되, 명시적으로 설정한 경우에는 그대로 사용한다.
            int defaultMaxChars = (mode == ChunkMode.SEMANTIC) ? 4000 : 1200;
            int maxChars = chunk.maxChars() > 0 ? chunk.maxChars() : defaultMaxChars;
            int overlapChars = chunk.overlapChars() >= 0 ? chunk.overlapChars() : 120;
            int minChars = chunk.minChars() > 0 ? chunk.minChars() : 40;
            chunk = new Chunk(maxChars, overlapChars, minChars, mode);
        }
    }

    /**
     * Control Plane Gateway (임베딩·QA: /api/ai/** 경유).
     */
    public record Gateway(
            String baseUrl,
            long embeddingTimeoutSeconds,
            int embeddingRateLimitRetryCount,
            long embeddingRateLimitRetryBaseDelayMs,
            long embeddingRateLimitRetryMaxDelayMs,
            int maxInMemorySizeBytes
    ) {
        public Gateway {
            if (baseUrl == null) {
                baseUrl = "";
            }
            if (embeddingTimeoutSeconds <= 0) {
                embeddingTimeoutSeconds = 120;
            }
            if (embeddingRateLimitRetryCount < 0) {
                embeddingRateLimitRetryCount = 0;
            }
            if (embeddingRateLimitRetryBaseDelayMs <= 0) {
                embeddingRateLimitRetryBaseDelayMs = 1000;
            }
            if (embeddingRateLimitRetryMaxDelayMs <= 0) {
                embeddingRateLimitRetryMaxDelayMs = 60_000;
            }
            if (maxInMemorySizeBytes <= 0) {
                maxInMemorySizeBytes = 10 * 1024 * 1024;
            }
        }
    }

    /**
     * Graph RAG Pass2(EXTRACT_RELATION) 설정.
     * - enabled=false 면 UPSERT 가 terminal 로 남고 Pass2 는 실행되지 않는다(비차단·opt-in).
     * - 문서 용량(file_size) 기준으로 서비스·가격 선호를 라우팅한다:
     *   threshold 이하 light(openai + CHEAP), 초과 heavy(claude + PREMIUM).
     * - modelCode 가 있으면 AIG 가 해당 모델을 쓰고, 없으면 modelPreference 로 priceLevel 최저/최고를 고른다.
     */
    public record Graph(
            boolean enabled,
            String lightAiServiceName, // 소용량 문서용 chat/stream aiService (기본 openai)
            String heavyAiServiceName, // 대용량 문서용 chat/stream aiService (기본 claude)
            long sizeThresholdBytes,   // 이 값 초과면 heavy, 이하면 light
            String lightModelCode,     // 소용량 문서용 모델 고정(선택). null이면 AIG CHEAP 라우팅
            String heavyModelCode,     // 대용량 문서용 모델 고정(선택). null이면 AIG PREMIUM 라우팅
            long timeoutSeconds,       // LLM 호출 타임아웃
            int chunkBatchSize,        // LLM 한 콜에 넣을 청크 수
            int maxCharsPerChunk,      // 청크당 프롬프트에 넣을 최대 글자수(초과 시 절단)
            long entityLinkTtlSeconds  // Pass1 entity:link 캐시 TTL(초). 0 이하면 만료 없음
    ) {
        public static final String PREFERENCE_CHEAP = "CHEAP";
        public static final String PREFERENCE_PREMIUM = "PREMIUM";

        public Graph {
            if (lightAiServiceName == null || lightAiServiceName.isBlank()) {
                lightAiServiceName = "openai";
            }
            if (heavyAiServiceName == null || heavyAiServiceName.isBlank()) {
                heavyAiServiceName = "claude";
            }
            if (sizeThresholdBytes <= 0) {
                sizeThresholdBytes = 1_048_576L;
            }
            if (timeoutSeconds <= 0) {
                timeoutSeconds = 900;
            }
            if (chunkBatchSize <= 0) {
                chunkBatchSize = 8;
            }
            if (maxCharsPerChunk <= 0) {
                maxCharsPerChunk = 4000;
            }
        }

        /** 문서 용량 기준 aiServiceName 선택. */
        public String resolveAiServiceName(Long fileSizeBytes) {
            return isHeavy(fileSizeBytes) ? heavyAiServiceName : lightAiServiceName;
        }

        /**
         * 문서 용량 기준 AIG modelPreference.
         * light → CHEAP(priceLevel 최저), heavy → PREMIUM(priceLevel 최고).
         */
        public String resolveModelPreference(Long fileSizeBytes) {
            return isHeavy(fileSizeBytes) ? PREFERENCE_PREMIUM : PREFERENCE_CHEAP;
        }

        /**
         * 문서 용량 기준 modelCode 선택.
         * null 이면 AIG 가 {@link #resolveModelPreference} 기준으로 고른다.
         */
        public String resolveModelCode(Long fileSizeBytes) {
            String code = isHeavy(fileSizeBytes) ? heavyModelCode : lightModelCode;
            return (code == null || code.isBlank()) ? null : code.trim();
        }

        private boolean isHeavy(Long fileSizeBytes) {
            return fileSizeBytes != null && fileSizeBytes > sizeThresholdBytes;
        }
    }

    /**
     * soft-delete 문서의 MinIO·Qdrant 물리 데이터 제거 스케줄.
     */
    public record Purge(boolean enabled, int retentionDays) {
        public Purge {
            if (retentionDays <= 0) {
                retentionDays = 100;
            }
        }
    }

    public record App(
            String role
    ) {}

    /**
     * 업로드 가능한 파일 사이즈 설정.
     * - maxFileSizeBytes: 확장자 제한이 없을 때 사용하는 전체 fallback 한도
     * - extensionLimits: 확장자별 개별 한도 (key: 소문자 확장자, value: bytes)
     *   예) pdf → 10MB, xlsx → 20MB
     *   벤치마크 결과 기반: PDF 30MB 시 60초 초과, PPTX 슬라이드 초과 시 IOException
     */
    public record Upload(
            Long maxFileSizeBytes,
            Map<String, Long> extensionLimits
    ) {}

    /** 텍스트 청킹 설정 */
    public record Chunk(
            int maxChars,       // 청크 최대 길이 (FIXED: 품질 튜닝값 / SEMANTIC: 임베딩 모델 토큰 한도 기반 하드 캡)
            int overlapChars,   // 강제 슬라이스 시 겹칠 길이
            int minChars,       // 마지막 청크가 너무 작을 때 병합 기준
            ChunkMode mode      // 청킹 알고리즘 선택 (FIXED | SEMANTIC)
    ) {}

    /**
     * Qdrant(벡터DB) 연결/검색 관련 설정.
     * - baseUrl: Qdrant 주소
     * - collection: 검색 대상 컬렉션
     * - timeoutMs: 요청 타임아웃
     * - topKDefault: Qdrant 검색 상한 (클라이언트 요청과 무관)
     */
    public record Qdrant(String baseUrl, String collection, long timeoutMs, int topKDefault) {}

    /**
     * 워커 동작 파라미터.
     * - 처리 주기, 재시도, 분할 단위, 재인덱싱 정책 등 운영 성격의 값을 포함한다.
     * - Consumer 프로세스 하나에서 PARSE→…→UPSERT 전체를 {@link com.ragservice.worker.worker.runner.ConsumerPipelineRunner}가 처리한다.
     */
    public record Worker(
            long delayMs,               // 워커 폴링/루프 딜레이(ms)
            Integer chunkPageSize,      // 청크 처리 시 페이지 단위 크기(대량 처리 분할)
            Integer embedLinesPerPart,  // 임베딩 part로 나눌 기준(라인/단위)
            String reindexPolicy,       // 재인덱싱 정책(문자열로 정책 선택)
            Boolean ensureCollection,   // 시작 시 Qdrant 컬렉션 존재 보장 여부
            Integer embedBatchSize,     // 임베딩 API 한 번에 보낼 청크 수(미설정 시 워커 기본 64)
            Long embedInterBatchDelayMs,// 임베딩 배치 간 대기(ms). 미설정 또는 0 이하면 대기 없음
            Integer maxRetry,           // 파이프라인 단계 실패 시 최대 재시도 횟수 (기본 3)
            Integer stuckJobTimeoutMinutes // RUNNING 잔류 job 을 PENDING 으로 복구하는 기준(분)
    ) {}

    /**
     * 스토리지 설정.
     * - OpenStorage를 통해 원본/중간 산출물 파일을 저장한다.
     * - keyPrefix는 storageKey 네임스페이스 분리 용도로 사용한다.
     */
    public record Storage(
            String keyPrefix,
            OpenStorage openstorage
    ) {
        /**
         * OpenStorage 연동 설정
         */
        public record OpenStorage(
                String baseUrl,
                String apiKey,
                String bucket
        ) {}
    }

    /**
     * 카테고리 관련 설정.
     *
     * @param allowed             처리/업로드 허용 카테고리 ID 목록(선택)
     * @param personalCategoryId  업로드 multipart {@code categoryId} 가 비었을 때 대체할 개인 카테고리 UUID
     */
    public record Categories(List<String> allowed, String personalCategoryId) {
        public Categories {
            if (allowed == null) {
                allowed = List.of();
            }
        }
    }

    /**
     * 질문/문서 임베딩 공통 설정.
     *
     * @param provider {@code ai-service} (기본): AI Connector 임베딩 API.
     */
    public record Embedding(
            Integer dimension,
            String provider
    ) {
        public Embedding {
            if (provider == null || provider.isBlank()) {
                provider = "ai-service";
            }
        }
    }

    /**
     * 외부 {@code langchain-service} 호출 설정 (별도 Git 레포에서 개발).
     */
    public record LangchainService(
            boolean enabled,
            String baseUrl,
            long timeoutMs,
            int maxInMemorySizeBytes
    ) {
        public LangchainService {
            if (baseUrl == null) {
                baseUrl = "";
            }
            if (timeoutMs <= 0) {
                timeoutMs = 30_000L;
            }
            if (maxInMemorySizeBytes <= 0) {
                maxInMemorySizeBytes = 10 * 1024 * 1024;
            }
        }
    }

    /**
     * langchain-service QA API 위임 경로 패턴.
     */
    public record Qa(
            String langchainPath
    ) {
        public Qa {
            if (langchainPath == null || langchainPath.isBlank()) {
                langchainPath = "/api/rag/{aiServiceName}/qa";
            }
        }
    }

    /**
     * LangChain 기반 통합 PCC 호출.
     * 원본은 presigned GET URL만 전달한다.
     */
    public record Pcc(
            boolean langchainEnabled,
            String baseUrl,
            String invokePath,
            int presignExpirySeconds,
            long timeoutMs,
            int maxInMemorySizeBytes
    ) {
        public Pcc {
            if (baseUrl == null) {
                baseUrl = "";
            }
            if (invokePath == null || invokePath.isBlank()) {
                invokePath = "/api/internal/rag/pcc/ingest";
            }
            if (presignExpirySeconds <= 0) {
                presignExpirySeconds = 3600;
            }
            if (timeoutMs <= 0) {
                timeoutMs = 120_000L;
            }
            if (maxInMemorySizeBytes <= 0) {
                maxInMemorySizeBytes = 32 * 1024 * 1024;
            }
        }
    }
}
