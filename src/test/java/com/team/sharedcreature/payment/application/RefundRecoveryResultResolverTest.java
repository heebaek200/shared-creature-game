package com.team.sharedcreature.payment.application;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** PG 응답만으로 환불 성공·실패를 잘못 확정하지 않는지 검증합니다. */
class RefundRecoveryResultResolverTest {
    private static final Instant NOW = Instant.parse("2026-11-13T00:05:00Z");
    private final RefundRecoveryResultResolver resolver = new RefundRecoveryResultResolver("store-1");
    private final RefundRecoveryPreparationService.RecoveryCommand command =
            new RefundRecoveryPreparationService.RecoveryCommand(
                    1L, 2L, 3L, 4L, "payment-1", 1000L, "KRW", "key", NOW);

    private PaymentSnapshot.CancellationSnapshot cancellation(String id, String status, long amount, Instant completed) {
        return new PaymentSnapshot.CancellationSnapshot(id, status, amount, NOW.minusSeconds(60), completed);
    }

    private PaymentSnapshot payment(String id, String store, long amount, String currency,
                                    PaymentSnapshot.Status status,
                                    List<PaymentSnapshot.CancellationSnapshot> cancellations) {
        return new PaymentSnapshot(id, store, "approval-1", status, amount, currency,
                "CARD", null, "LIVE", NOW.minusSeconds(600), cancellations);
    }

    private PaymentSnapshot payment(PaymentSnapshot.Status status, PaymentSnapshot.CancellationSnapshot... entries) {
        return payment("payment-1", "store-1", 1000, "KRW", status, List.of(entries));
    }

    @Test
    void 취소ID를_받지_못한_Timeout도_단일_전액취소_성공이면_복구한다() {
        var result = resolver.resolve(command, payment(PaymentSnapshot.Status.CANCELLED,
                cancellation("cancel-1", "SUCCEEDED", 1000, NOW)), null);
        assertThat(result.outcome()).isEqualTo(RefundGateway.Outcome.SUCCESS);
        assertThat(result.providerTransactionId()).isEqualTo("cancel-1");
        assertThat(result.processedAt()).isEqualTo(NOW);
    }

    @ParameterizedTest
    @ValueSource(strings = {"FAILED", "REQUESTED"})
    void 저장된_취소ID와_일치하는_실패와_대기를_구분한다(String status) {
        var result = resolver.resolve(command, payment(PaymentSnapshot.Status.PAID,
                cancellation("cancel-1", status, 1000, null)), "cancel-1");
        assertThat(result.outcome()).isEqualTo("FAILED".equals(status)
                ? RefundGateway.Outcome.DEFINITIVE_FAILURE : RefundGateway.Outcome.PENDING);
        assertThat(result.processedAt()).isNull();
    }

    @ParameterizedTest
    @ValueSource(strings = {"FAILED", "REQUESTED"})
    void 취소ID가_없으면_과거_실패나_대기를_이번_결과로_추정하지_않는다(String status) {
        assertUnknown(payment(PaymentSnapshot.Status.PAID,
                cancellation("old-cancel", status, 1000, null)), null);
    }

    @ParameterizedTest
    @ValueSource(strings = {"payment", "store", "amount", "currency"})
    void 결제_상점_금액_통화가_다르면_성공을_확정하지_않는다(String mismatch) {
        var snapshot = payment("payment".equals(mismatch) ? "other" : "payment-1",
                "store".equals(mismatch) ? "other" : "store-1",
                "amount".equals(mismatch) ? 999 : 1000,
                "currency".equals(mismatch) ? "USD" : "KRW",
                PaymentSnapshot.Status.CANCELLED,
                List.of(cancellation("cancel-1", "SUCCEEDED", 1000, NOW)));
        assertUnknown(snapshot, "cancel-1");
    }

    @ParameterizedTest
    @ValueSource(strings = {"partial", "time", "state", "id", "duplicate"})
    void 취소_성공의_근거가_부족하거나_모순되면_미확정이다(String reason) {
        var entry = cancellation("cancel-1", "SUCCEEDED", "partial".equals(reason) ? 500 : 1000,
                "time".equals(reason) ? null : NOW);
        var snapshot = payment("state".equals(reason) ? PaymentSnapshot.Status.PAID : PaymentSnapshot.Status.CANCELLED,
                "duplicate".equals(reason) ? new PaymentSnapshot.CancellationSnapshot[]{entry, entry}
                        : new PaymentSnapshot.CancellationSnapshot[]{entry});
        assertUnknown(snapshot, "id".equals(reason) ? "other-cancel" : "cancel-1");
    }

    @Test
    void 취소ID가_없고_여러_거래가_있으면_임의로_고르지_않는다() {
        assertUnknown(payment(PaymentSnapshot.Status.CANCELLED,
                cancellation("old", "FAILED", 1000, null),
                cancellation("new", "SUCCEEDED", 1000, NOW)), null);
    }

    @Test
    void 조회결과가_없어도_실패로_확정하지_않는다() {
        assertUnknown(null, "cancel-1");
        assertUnknown(payment(PaymentSnapshot.Status.PAID), null);
    }

    private void assertUnknown(PaymentSnapshot snapshot, String id) {
        var result = resolver.resolve(command, snapshot, id);
        assertThat(result.outcome()).isEqualTo(RefundGateway.Outcome.UNKNOWN);
        assertThat(result.processedAt()).isNull();
        assertThat(result.paymentId()).isEqualTo("payment-1");
    }
}
