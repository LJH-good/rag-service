package com.init.worker.domain.enums;

/**
 * 포탈/폴링 UI용 처리 상태.
 * raw {@link RagJobStatus}·{@link FileStatus} 조합보다 화면 표시에 적합하다.
 */
public enum ProcessingDisplayStatus {
    PROCESSING,
    SUCCEEDED,
    FAILED
}
