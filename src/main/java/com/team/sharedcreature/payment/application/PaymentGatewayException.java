package com.team.sharedcreature.payment.application;

/** PortOne 조회의 확정 실패와 결과 불명 통신 오류를 구분합니다. */
public class PaymentGatewayException extends RuntimeException {

    private final Kind kind;

    public PaymentGatewayException(Kind kind, String message, Throwable cause) {
        super(message, cause);
        this.kind = kind;
    }

    public Kind kind() {
        return kind;
    }

    /** 외부 오류를 내부에서 판단하는 최소 분류입니다. */
    public enum Kind {
        NOT_FOUND,
        UNAVAILABLE,
        INVALID_RESPONSE
    }
}
