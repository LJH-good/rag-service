package com.ragservice.worker.error.code;

import java.util.Map;

/**
 * 에러 코드(에러 메시지 템플릿) 규격 인터페이스.
 *
 * - template(): 사람이 읽을 메시지 템플릿을 반환한다.
 * - render(args): 템플릿의 {key} 형태 플레이스홀더를 args 값으로 치환해 최종 메시지를 만든다.
 *
 * 예)
 * template = "storage-service error: status={status}"
 * args = {"status": 500}
 * => "storage-service error: status=500"
 */
public interface ErrorCode {

    /** 에러 메시지 템플릿(치환 전 원본) */
    String template();

    /** 템플릿 문자열에 args를 치환해 최종 메시지를 만든다. */
    default String render(Map<String, Object> args) {
        String msg = template();
        if (args == null) return msg;

        for (var e : args.entrySet()) {
            msg = msg.replace("{" + e.getKey() + "}", String.valueOf(e.getValue()));
        }
        return msg;
    }
}
