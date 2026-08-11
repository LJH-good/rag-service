package com.init.worker.util;

import java.util.UUID;

/**
 * 시스템 전반에서 사용할 문자열 ID를 생성하는 유틸리티 클래스.
 *
 * - UUID 기반으로 충돌 가능성을 낮춘 식별자를 만든다.
 * - 하이픈(-)을 제거한 형태를 사용해 DB 키, 문서 ID, Job ID 등에 바로 쓰기 쉽게 한다.
 * - 상태 없는 정적 유틸 클래스이므로 인스턴스 생성 없이 사용한다.
 */
public class IdGenerator {

    /** 새로운 랜덤 ID 생성 */
    public static UUID newId() {
        return UUID.randomUUID();
    }
}
