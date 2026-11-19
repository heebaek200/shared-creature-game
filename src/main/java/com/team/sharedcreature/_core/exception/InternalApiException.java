package com.team.sharedcreature._core.exception;

/** 사전에 정한 사용자 안내 문구와 내부 오류의 원인을 함께 전달합니다. */
public final class InternalApiException extends RuntimeException {

    public InternalApiException(String clientMessage) {
        this(clientMessage, null);
    }

    public InternalApiException(String clientMessage, Throwable cause) {
        super(clientMessage, cause);
    }
}
