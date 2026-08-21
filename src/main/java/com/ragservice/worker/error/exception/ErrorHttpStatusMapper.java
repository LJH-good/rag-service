package com.ragservice.worker.error.exception;

import com.ragservice.worker.error.code.ErrorCode;
import com.ragservice.worker.error.code.ErrorCodes;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

/**
 * ErrorCode → HTTP Status 매핑 규칙.
 *
 * 목적:
 * - 같은 ErrorCode는 항상 같은 HTTP 상태로 응답하게 만들어 API 응답을 일관되게 유지한다.
 *
 * 정책(현재 코드 기준):
 * - Api.* 중 입력값 문제 → 400
 * - Api.FILE_NOT_FOUND → 404
 * - Api.INTERNAL_* → 500
 * - Storage.*(외부 연동/요청 실패) → 502(BAD_GATEWAY) 계열
 */
@Component
public class ErrorHttpStatusMapper {

    public HttpStatus map(ErrorCode code) {
        if (code == null) return HttpStatus.INTERNAL_SERVER_ERROR;

        // API 입력/리소스 계열 에러
        if (code instanceof ErrorCodes.Api api) {
            return switch (api) {
                // 클라이언트 요청이 잘못된 경우
                case BAD_REQUEST_AI_SERVICE_NAME_REQUIRED,
                     BAD_REQUEST_UNSUPPORTED_AI_SERVICE_NAME,
                     BAD_REQUEST_TRANSACTION_ID_REQUIRED,
                     BAD_REQUEST_FILE_REQUIRED,
                     BAD_REQUEST_STORAGE_KEY_REQUIRED,
                     BAD_REQUEST_INVALID_STORAGE_KEY,
                     BAD_REQUEST_CATEGORY_ID_REQUIRED,
                     BAD_REQUEST_USER_TYPE_REQUIRED,
                     BAD_REQUEST_USER_ID_REQUIRED,
                     BAD_REQUEST_DOCUMENT_SCOPE_INVALID,
                     BAD_REQUEST_FILE_READ_FAILED,
                     INTERNAL_JOB_CREATE_FAILED,
                     BAD_REQUEST_API_KEY_REQUIRED,
                     BAD_REQUEST_FILE_SIZE_EXCEEDED,
                     BAD_REQUEST_QUESTION_CONTENT_REQUIRED,
                     BAD_REQUEST_SEARCH_QUERY_REQUIRED,
                     QUERY_EMBEDDING_EMPTY,
                     QUERY_EMBEDDING_DIMENSION_MISMATCH,
                     CATEGORY_INACTIVE -> HttpStatus.BAD_REQUEST;

                // 요청한 리소스가 없는 경우
                case FILE_NOT_FOUND,
                     DOCUMENT_NOT_FOUND -> HttpStatus.NOT_FOUND;

                // 중복 실행(진행 중 job 존재)은 충돌로 처리
                case ACTIVE_JOB_ALREADY_EXISTS,
                     CONFLICT_UPLOAD_ALREADY_IN_PROGRESS -> HttpStatus.CONFLICT;

                // 외부 임베딩 API 호출/응답 이상
                case EMBEDDING_API_REQUEST_FAILED,
                     EMBEDDING_API_RESPONSE_NULL,
                     EMBEDDING_API_RESPONSE_EMPTY,
                     EMBEDDING_API_RESPONSE_SIZE_MISMATCH,
                     EMBEDDING_RESULT_MISSING,
                     CHAT_API_REQUEST_FAILED,
                     CHAT_API_RESPONSE_EMPTY -> HttpStatus.BAD_GATEWAY;

                // 내부 검색 데이터(payload) 이상
                case RETRIEVAL_PAYLOAD_REQUIRED_KEY_MISSING,
                     RETRIEVAL_PAYLOAD_INVALID_INT -> HttpStatus.INTERNAL_SERVER_ERROR;

                // 카테고리/데이터소스/Job 리소스 없음
                case CATEGORY_NOT_FOUND,
                     DATASOURCE_NOT_FOUND,
                     JOB_NOT_FOUND,
                     QA_LOG_NOT_FOUND,
                     QA_CITATIONS_NOT_FOUND,
                     DOCUMENT_FILE_NOT_FOUND -> HttpStatus.NOT_FOUND;

                // 이미 삭제된 리소스 / 중복 재인덱싱
                case CATEGORY_ALREADY_DELETED,
                     DATASOURCE_ALREADY_DELETED,
                     REINDEX_JOB_ALREADY_ACTIVE -> HttpStatus.CONFLICT;

                // 스토리지 키 없음
                case FILE_STORAGE_KEY_MISSING -> HttpStatus.BAD_REQUEST;

                // 지원하지 않는 파일 포맷
                case UNSUPPORTED_FILE_FORMAT -> HttpStatus.UNSUPPORTED_MEDIA_TYPE;

                // 어휘 관리
                case VOCAB_ENTRY_ALREADY_EXISTS -> HttpStatus.CONFLICT;
                case VOCAB_ENTRY_NOT_FOUND -> HttpStatus.NOT_FOUND;
            };
        }

        // 스토리지 연동 계열 에러(다운스트림 장애로 간주)
        if (code instanceof ErrorCodes.Storage) {
            return HttpStatus.BAD_GATEWAY; // 또는 SERVICE_UNAVAILABLE
        }

        // 스토리지 연동 계열 에러(다운스트림 장애로 간주)
        return HttpStatus.INTERNAL_SERVER_ERROR;
    }

}
