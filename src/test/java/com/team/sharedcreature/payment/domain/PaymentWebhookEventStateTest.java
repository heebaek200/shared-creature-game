package com.team.sharedcreature.payment.domain;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/** 실제 대기 없이 고정 시각으로 Webhook 복구 기준과 재시도 이력 보존을 검증합니다. */
class PaymentWebhookEventStateTest {
    private static final Instant START = Instant.parse("2026-11-16T00:00:00Z");

    @Test
    void 대기상태는_처리시작후_정확히_5분부터_복구할수있다() {
        PaymentWebhookEvent event = received();

        assertThat(event.getProcessingStatus()).isEqualTo(WebhookProcessingStatus.PENDING);
        assertThat(event.isRecoverable(START.minusNanos(1))).isFalse();
        // cutoff는 호출자가 현재 시각에서 5분을 뺀 값입니다.
        assertThat(event.isRecoverable(START)).isTrue();
    }

    @Test
    void 실패상태는_최초접수가_아닌_마지막실패시각으로_복구를_판정한다() {
        PaymentWebhookEvent event = received();
        Instant failedAt = START.plusSeconds(600);
        event.fail("PAYMENT_PENDING", failedAt);

        assertThat(event.getProcessingStatus()).isEqualTo(WebhookProcessingStatus.FAILED);
        assertThat(event.isRecoverable(failedAt.minusNanos(1))).isFalse();
        assertThat(event.isRecoverable(failedAt)).isTrue();
    }

    @ParameterizedTest
    @EnumSource(value = WebhookProcessingStatus.class, names = {"PROCESSED", "IGNORED"})
    void 종료된_이벤트는_오래되어도_복구대상이_아니다(WebhookProcessingStatus status) {
        PaymentWebhookEvent event = received();
        if (status == WebhookProcessingStatus.PROCESSED) {
            event.complete(null, null, START);
        } else {
            event.ignore(START);
        }

        assertThat(event.getProcessingStatus()).isEqualTo(status);
        assertThat(event.isRecoverable(START.plusSeconds(3600))).isFalse();
    }

    @Test
    void 재시도는_시작시각과_횟수를_갱신하고_원본이벤트를_보존한다() {
        PaymentWebhookEvent event = received();
        event.fail("PG_UNAVAILABLE", START.plusSeconds(1));
        Instant retriedAt = START.plusSeconds(600);
        event.retry(retriedAt);

        assertThat(event.getProcessingStatus()).isEqualTo(WebhookProcessingStatus.PENDING);
        assertThat(event.getAttemptCount()).isEqualTo(2);
        assertThat(event.getProcessingStartedAt()).isEqualTo(retriedAt);
        assertThat(event.getProcessedAt()).isNull();
        assertThat(event.isRecoverable(retriedAt.minusNanos(1))).isFalse();
        assertThat(event.getProviderEventId()).isEqualTo("event-62");
        assertThat(event.getReceivedAt()).isEqualTo(START);
        assertThat(event.getPayloadHash()).isEqualTo("a".repeat(64));
        assertThat(event.getFailureDetail()).isEqualTo("PG_UNAVAILABLE");
    }

    /** 원장 연결이 아직 없는 이벤트도 기존처럼 보존할 수 있습니다. */
    private PaymentWebhookEvent received() {
        return PaymentWebhookEvent.received("event-62", "payment-62", null,
                "Transaction.Paid", "a".repeat(64), "{}", START);
    }
}
