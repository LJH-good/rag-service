package com.ragservice.worker.domain.enums;

/**
 * 문서 처리 Job(rag_document_jobs)의 상태.
 * - 한 문서에 대한 실행 단위 상태를 표현한다.
 */
public enum RagJobStatus {
    RUNNING,        // 실행 중(파이프라인 단계 진행 중)
    SUCCEEDED,      // 전체 단계 성공 종료
    FAILED,         // 실패 종료(어느 단계에서 실패했는지 errorStep 등으로 기록)
    PENDING         // 지연
}