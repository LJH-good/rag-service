package com.init.worker.error.exception;

import com.init.worker.error.code.ErrorCode;

import java.util.Collections;
import java.util.Map;

/**
 * 애플리케이션 표준 예외.
 *
 * 목적:
 * - "어떤 에러인지"(ErrorCode)와 "표시할 메시지"(template + args)를 한 번에 묶어서 던진다.
 * - GlobalExceptionHandler에서 code/message 형태로 통일된 JSON 응답을 만들 수 있게 한다.
 *
 * 구성:
 * - errorCode: 에러 종류(예: ErrorCodes.Api.BAD_REQUEST_FILE_REQUIRED)
 * - args: 템플릿 치환용 값들(예: {status}, {reason} 등)
 */
public class AppException extends RuntimeException {

    private final ErrorCode errorCode;
    private final Map<String, Object> args;

    /** args 없이도 쉽게 던지도록 만든 생성자(치환 값이 없을 때 사용) */
    public AppException(ErrorCode errorCode) {
        this(errorCode, Map.of());
    }

    /**
     * 기본 생성자.
     * - super(message)는 errorCode.template()에 args를 render한 결과를 사용한다.
     */
    public AppException(ErrorCode errorCode, Map<String, Object> args) {
        super(errorCode.render(args));
        this.errorCode = errorCode;
        this.args = (args == null) ? Collections.emptyMap() : Map.copyOf(args);
    }

    /**
     * 원인(cause)까지 포함하는 생성자.
     * - 외부 호출 실패/IO 오류 등에서 원인을 함께 넘겨 디버깅/로그 추적에 활용한다.
     */
    public AppException(ErrorCode errorCode, Map<String, Object> args, Throwable cause) {
        super(errorCode.render(args), cause);
        this.errorCode = errorCode;
        this.args = (args == null) ? Collections.emptyMap() : Map.copyOf(args);
    }

    public ErrorCode getErrorCode() {
        return errorCode;
    }

    /**
     * 응답에 내려줄 code 문자열.
     * - ErrorCode가 enum이면 enum name()을 코드로 사용 (예: BAD_REQUEST_FILE_REQUIRED)
     * - 아니면 클래스명을 코드로 사용
     */
    public String getCode() {
        if (errorCode == null) return null;

        if (errorCode instanceof Enum<?> en) {
            return en.name();
        }
        return errorCode.getClass().getSimpleName();
    }

    /** 템플릿 치환에 사용된 인자(예: 외부 API status/body). */
    public Map<String, Object> getArgs() {
        return args;
    }

}
