package com.ragservice.worker.error.code;

/**
 * 시스템에서 사용하는 에러 코드 모음.
 *
 * 구성 방식
 * - 그룹별 enum으로 분리해서 관리한다.
 *   - Storage: 외부 스토리지 연동/요청 오류
 *   - Api: 요청 파라미터 오류, 리소스 없음 등 API 레벨 오류
 *   - Config: 서버 설정 누락/비정상 설정 오류
 *
 * 장점
 * - 코드(enum name)와 메시지(template)를 한 곳에서 일관되게 관리 가능
 * - AppException + GlobalExceptionHandler에서 code/message를 표준화 가능
 */
public final class ErrorCodes {

    private ErrorCodes() {}

    /**
     * 스토리지 연동 계열 에러 코드
     */
    public enum Storage implements ErrorCode {

        /** MinIO 업로드/다운로드 처리 실패 */
        STORAGE_REQUEST_ERROR("storage request error: {reason}");

        private final String template;

        Storage(String template) {
            this.template = template;
        }

        @Override
        public String template() {
            return template;
        }
    }

    /**
     * API 입력/리소스 계열 에러 코드
     * - 요청 파라미터 검증, 파일 없음, 리소스 없음 등을 표현한다.
     */
    public enum Api implements ErrorCode {

        /** 필수 파라미터(storage_key) 누락/공백 */
        BAD_REQUEST_STORAGE_KEY_REQUIRED("storage_key is required"),

        /** multipart 파일 파트(file) 누락/비어있음 */
        BAD_REQUEST_FILE_REQUIRED("file is required"),

        /** categoryId 누락/공백 */
        BAD_REQUEST_CATEGORY_ID_REQUIRED("categoryId is required"),

        /** userType 누락/공백 */
        BAD_REQUEST_USER_TYPE_REQUIRED("userType is required"),

        /** userId 누락/공백 */
        BAD_REQUEST_USER_ID_REQUIRED("userId is required"),

        /** aiServiceName 누락/공백 */
        BAD_REQUEST_AI_SERVICE_NAME_REQUIRED("aiServiceName is required"),

        /** 지원하지 않는 aiServiceName */
        BAD_REQUEST_UNSUPPORTED_AI_SERVICE_NAME("unsupported aiServiceName: {aiServiceName}"),

        /** 문서 scope 값이 유효하지 않음 */
        BAD_REQUEST_DOCUMENT_SCOPE_INVALID("invalid document scope: {scope}"),

        /** X-Transaction-Id 헤더 누락/공백 */
        BAD_REQUEST_TRANSACTION_ID_REQUIRED("X-Transaction-Id header is required"),

        /** 업로드 파일 크기 제한 초과 */
        BAD_REQUEST_FILE_SIZE_EXCEEDED("file size exceeds limit: max={maxFileSizeBytes} bytes"),

        /** storage_key가 루트 탈출 등 비정상일 때 */
        BAD_REQUEST_INVALID_STORAGE_KEY("invalid storage_key"),

        /** 대상 파일 리소스가 없을 때 */
        FILE_NOT_FOUND("file not found"),

        /** 문서 리소스가 없을 때 */
        DOCUMENT_NOT_FOUND("document not found: {documentId}"),

        /** 활성 작업이 이미 존재할 때 */
        ACTIVE_JOB_ALREADY_EXISTS("active job already exists. documentId={documentId}"),

        /** multipart 업로드 파일을 읽는 중 실패한 경우 */
        BAD_REQUEST_FILE_READ_FAILED("failed to read upload file: {reason}"),
        INTERNAL_JOB_CREATE_FAILED("An error occurred while creating the document processing job"),

        /** 사용자 API Key 헤더(X-API-Key) 누락/공백 */
        BAD_REQUEST_API_KEY_REQUIRED("X-API-Key header is required"),

        CONFLICT_UPLOAD_ALREADY_IN_PROGRESS("upload already in progress: {reason}"),

        BAD_REQUEST_QUESTION_CONTENT_REQUIRED("question content is blank"),
        BAD_REQUEST_SEARCH_QUERY_REQUIRED("searchQuery is required"),
        QUERY_EMBEDDING_EMPTY("query embedding is empty"),
        QUERY_EMBEDDING_DIMENSION_MISMATCH("query embedding dimension mismatch. expected={expected}, actual={actual}"),

        EMBEDDING_API_REQUEST_FAILED("embedding api request failed: {reason}"),
        EMBEDDING_API_RESPONSE_NULL("embedding api response is null"),
        EMBEDDING_API_RESPONSE_EMPTY("embedding api response results are empty"),
        EMBEDDING_API_RESPONSE_SIZE_MISMATCH("embedding api response size mismatch. expected={expected}, actual={actual}"),
        EMBEDDING_RESULT_MISSING("embedding result missing for itemId={itemId}"),
        CHAT_API_REQUEST_FAILED("chat api request failed: {reason}"),
        CHAT_API_RESPONSE_EMPTY("chat api response is empty"),

        RETRIEVAL_PAYLOAD_REQUIRED_KEY_MISSING("retrieval payload missing required key: {key}"),
        RETRIEVAL_PAYLOAD_INVALID_INT("retrieval payload invalid int key: {key}, value={value}"),

        // 카테고리 관련
        CATEGORY_NOT_FOUND("category not found: {categoryId}"),
        CATEGORY_ALREADY_DELETED("category already deleted: {categoryId}"),
        CATEGORY_INACTIVE("category is inactive: {categoryId}"),

        // 어드민 데이터소스 관련
        DATASOURCE_NOT_FOUND("datasource not found: {documentId}"),
        DATASOURCE_ALREADY_DELETED("datasource already deleted: {documentId}"),

        // 재임베딩 관련
        REINDEX_JOB_ALREADY_ACTIVE("reindex job already active for document: {documentId}"),

        // Job 조회 관련
        JOB_NOT_FOUND("job not found: {jobId}"),

        // QA 조회 관련
        QA_LOG_NOT_FOUND("qa log not found: {messageId}"),
        QA_CITATIONS_NOT_FOUND("qa citations not found: {messageId}"),

        // 파일 다운로드 관련
        DOCUMENT_FILE_NOT_FOUND("document file not found: {documentId}"),
        FILE_STORAGE_KEY_MISSING("file storage key is missing for document: {documentId}"),

        // 지원하지 않는 파일 포맷
        UNSUPPORTED_FILE_FORMAT("unsupported file format: {fileName}"),

        // 그래프 어휘 관리
        VOCAB_ENTRY_ALREADY_EXISTS("vocabulary entry already exists: kind={kind} name={name}"),
        VOCAB_ENTRY_NOT_FOUND("vocabulary entry not found: kind={kind} name={name}");
        ;

        private final String template;

        Api(String template) {
            this.template = template;
        }

        @Override
        public String template() {
            return template;
        }
    }

    /**
     * 서버 설정 계열 에러 코드
     * - application.yml / 환경변수 누락 등 실행 환경 설정 문제를 표현한다.
     */
    public enum Config implements ErrorCode {

        /** rag.storage.minio 전체 설정 누락 */
        MINIO_CONFIG_REQUIRED("rag.storage.minio is required"),

        /** MinIO endpoint 누락 */
        MINIO_ENDPOINT_REQUIRED("rag.storage.minio.endpoint is required"),

        /** MinIO access-key 누락 */
        MINIO_ACCESS_KEY_REQUIRED("rag.storage.minio.access-key is required"),

        /** MinIO secret-key 누락 */
        MINIO_SECRET_KEY_REQUIRED("rag.storage.minio.secret-key is required"),

        /** MinIO bucket 누락 */
        MINIO_BUCKET_REQUIRED("rag.storage.minio.bucket is required"),

        /** 업로드 최대 파일 크기 설정 누락 */
        UPLOAD_MAX_FILE_SIZE_REQUIRED("rag.upload.max-file-size-bytes is required"),
        LANGCHAIN_PCC_ENABLED_REQUIRED("rag.pcc.langchain-enabled must be true");

        private final String template;

        Config(String template) {
            this.template = template;
        }
        
        @Override
        public String template() {
            return template;
        }
    }

}