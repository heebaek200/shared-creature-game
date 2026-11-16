package com.team.sharedcreature._core.realtime;

import org.springframework.messaging.MessagingException;

/** STOMP ERROR의 message Header로 전달할 계약 오류를 표현합니다. */
public class StompAccessException extends MessagingException {

    private final String code;

    /** 클라이언트가 분기할 기존 오류 코드로 예외를 생성합니다. */
    public StompAccessException(String code) {
        super(code);
        this.code = code;
    }

    /** 계약된 STOMP 오류 코드를 반환합니다. */
    public String code() {
        return code;
    }
}
