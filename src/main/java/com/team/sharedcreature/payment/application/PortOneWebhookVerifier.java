package com.team.sharedcreature.payment.application;

import com.team.sharedcreature.payment.infrastructure.PortOneProperties;
import io.portone.sdk.server.errors.WebhookVerificationException;
import io.portone.sdk.server.webhook.WebhookVerifier;
import org.springframework.stereotype.Component;

/** PortOne 공식 SDK로 원본 Webhook Body의 Standard Webhooks 서명을 검증합니다. */
@Component
public class PortOneWebhookVerifier {

    private final PortOneProperties properties;

    public PortOneWebhookVerifier(PortOneProperties properties) {
        this.properties = properties;
    }

    /** 서명이 유효하지 않으면 안전한 내부 예외로 변환합니다. */
    public void verify(String body, String messageId, String signature, String timestamp) {
        try {
            new WebhookVerifier(properties.webhookSecret())
                    .verify(body, messageId, signature, timestamp);
        } catch (WebhookVerificationException | RuntimeException exception) {
            throw new InvalidWebhookSignatureException(exception);
        }
    }

    /** Webhook 서명 오류를 일반 API 오류 Envelope과 분리합니다. */
    public static final class InvalidWebhookSignatureException extends RuntimeException {
        InvalidWebhookSignatureException(Throwable cause) {
            super(cause);
        }
    }
}
