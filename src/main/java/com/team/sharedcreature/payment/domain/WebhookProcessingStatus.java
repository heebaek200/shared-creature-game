package com.team.sharedcreature.payment.domain;

/** PortOne Webhook의 내부 처리 상태입니다. */
public enum WebhookProcessingStatus {
    PENDING,
    PROCESSED,
    IGNORED,
    FAILED
}
