package com.ragservice.worker.domain.enums;

/**
 * Job 트리거 타입(왜 이 Job이 생성되었는지).
 */
public enum RagTriggerType {
    UPLOAD,          // 문서 업로드로 인해 자동 시작된 처리
    REINDEX,         // 재인덱싱(재처리) 요청으로 시작
    MANUAL,          // 사용자가 수동으로 실행
    SCHEDULED        // 스케줄러에 의해 자동 실행
}