package com.init.worker.domain.enums;

/** rag_document_files.status — 물리 파일 업로드 상태 */
public enum FileStatus {
    PENDING,   // 파일 row 생성, 스토리지 업로드 전
    UPLOADED,  // 스토리지 업로드 완료
    INDEXED,   // 파이프라인 완료 (벡터 DB 색인 성공)
    FAILED     // 업로드 또는 파이프라인 실패
}
