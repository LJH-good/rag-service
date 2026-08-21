package com.ragservice.worker.domain.enums;

/**
 * Job 파이프라인 단계.
 * - DB CHECK 제약: PARSE | CLEAN | CHUNK | EMBED | UPSERT | EXTRACT_RELATION
 * - PCC 는 Java 내부 라우팅 전용 상수(DB 에 직접 저장되지 않음).
 */
public enum RagJobStep {
    PARSE,
    CLEAN,
    CHUNK,
    /** LangChain PCC(parse·clean·chunk 통합) — DB에 저장 안 됨, 워커 라우팅 전용 */
    PCC,
    /**
     * Pass1(그래프 엔티티 추출) — graph.enabled 시 PCC 를 대체하는 파이프라인 진입 단계.
     * parse-clean → 엔티티 추출(LLM) → 정리본 청킹을 한 워커 실행에서 수행한다.
     * 정리본은 저장하지 않고(인메모리) 청크만 저장하므로 DB current_step 으로 잔류하지 않는다 → 라우팅 전용.
     */
    EXTRACT_ENTITY,
    EMBED,
    UPSERT,
    /** Pass2(그래프 관계 추출) — UPSERT 뒤 terminal 단계 */
    EXTRACT_RELATION;

    /**
     * DB에 저장할 때 사용할 step 값.
     * PCC·EXTRACT_ENTITY → PARSE 로 매핑 (DB CHECK 제약 준수, 라우팅 전용 상수).
     */
    public RagJobStep toDbStep() {
        return (this == PCC || this == EXTRACT_ENTITY) ? PARSE : this;
    }
}
