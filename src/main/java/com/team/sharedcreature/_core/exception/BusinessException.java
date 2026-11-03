package com.team.sharedcreature._core.exception;

import org.springframework.http.HttpStatus;

/**
 * 예측 가능한 업무 실패를 HTTP 상태와 안정적인 코드로 표현합니다.
 */
public class BusinessException extends RuntimeException {

    private final HttpStatus status;
    private final String code;

    /**
     * 업무 예외를 생성합니다.
     *
     * @param status 반환할 HTTP 상태
     * @param code 클라이언트 분기용 오류 코드
     * @param message 사용자에게 표시할 안전한 메시지
     */
    public BusinessException(HttpStatus status, String code, String message) {
        super(message);
        this.status = status;
        this.code = code;
    }

    public HttpStatus status() {
        return status;
    }

    public String code() {
        return code;
    }
}
