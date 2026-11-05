package com.team.sharedcreature._core.exception;

/**
 * 예측 가능한 업무 실패를 HTTP 상태와 안정적인 코드로 표현합니다.
 */
public class BusinessException extends RuntimeException {

    private final ErrorCode errorCode;

    /**
     * 업무 예외를 생성합니다.
     *
     * @param errorCode 안정적인 API 오류 코드
     * @param message 사용자에게 표시할 안전한 메시지
     */
    public BusinessException(ErrorCode errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }

    /** 오류 코드의 기본 메시지를 사용하는 업무 예외를 생성합니다. */
    public BusinessException(ErrorCode errorCode) {
        this(errorCode, errorCode.defaultMessage());
    }

    public ErrorCode errorCode() {
        return errorCode;
    }

    public org.springframework.http.HttpStatus status() {
        return errorCode.status();
    }

    public String code() {
        return errorCode.code();
    }
}
