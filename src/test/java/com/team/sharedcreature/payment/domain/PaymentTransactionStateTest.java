package com.team.sharedcreature.payment.domain;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import static com.team.sharedcreature.avatar.support.AvatarTestFixtures.*;
import static org.assertj.core.api.Assertions.*;

/** 거래 Enum 전환 후 취소 결과·반복 반영·확정 결과 보호 규칙을 검증합니다. */
class PaymentTransactionStateTest {

    @ParameterizedTest
    @EnumSource(PaymentTransactionStatus.class)
    void 취소결과_반영과_동일결과_재반영에서도_거래정보를_보존한다(PaymentTransactionStatus status) {
        var transaction = PaymentTransaction.cancellationRequested(
                unusedItem(10L, 1L, "HEAD").getPurchase(), CREATED_AT);
        var processedAt = status == PaymentTransactionStatus.SUCCESS
                || status == PaymentTransactionStatus.DEFINITIVE_FAILURE ? NOW : null;

        assertThat(transaction.applyCancellationResult(status, "cancel-1", processedAt, "PG", NOW)).isTrue();
        assertThat(transaction.getTransactionType()).isEqualTo(PaymentTransactionType.CANCELLATION);
        assertThat(transaction.getStatus()).isEqualTo(status);
        assertThat(transaction.getProcessedAt()).isEqualTo(processedAt);

        assertThat(transaction.applyCancellationResult(
                status, "cancel-1", processedAt, "PG", NOW.plusSeconds(1))).isFalse();
        assertThat(transaction.getUpdatedAt()).isEqualTo(NOW);
    }

    @ParameterizedTest
    @EnumSource(value = PaymentTransactionStatus.class, names = {"SUCCESS", "DEFINITIVE_FAILURE"})
    void 확정된_취소결과는_늦은_미확정응답으로_덮어쓰지_않는다(PaymentTransactionStatus status) {
        var transaction = PaymentTransaction.cancellationRequested(
                unusedItem(10L, 1L, "HEAD").getPurchase(), CREATED_AT);
        transaction.applyCancellationResult(status, "cancel-1", NOW, "PG", NOW);

        assertThat(transaction.applyCancellationResult(PaymentTransactionStatus.UNKNOWN,
                null, null, null, NOW.plusSeconds(1))).isFalse();
        assertThat(transaction.getStatus()).isEqualTo(status);
        assertThat(transaction.getProviderTransactionId()).isEqualTo("cancel-1");
        assertThat(transaction.getProcessedAt()).isEqualTo(NOW);
        assertThat(transaction.getUpdatedAt()).isEqualTo(NOW);
    }

    @Test
    void 승인거래에는_취소결과를_반영하지_않는다() {
        var transaction = PaymentTransaction.approved(
                unusedItem(10L, 1L, "HEAD").getPurchase(), "approval-1", 1000L, NOW, NOW);

        assertThatThrownBy(() -> transaction.applyCancellationResult(
                PaymentTransactionStatus.UNKNOWN, null, null, null, NOW))
                .isInstanceOf(IllegalStateException.class);
        assertThat(transaction.getTransactionType()).isEqualTo(PaymentTransactionType.APPROVAL);
        assertThat(transaction.getStatus()).isEqualTo(PaymentTransactionStatus.SUCCESS);
    }

    @Test
    void 취소성공의_필수정보가_없으면_이전상태를_보존한다() {
        var transaction = PaymentTransaction.cancellationRequested(
                unusedItem(10L, 1L, "HEAD").getPurchase(), CREATED_AT);

        assertThatThrownBy(() -> transaction.applyCancellationResult(
                PaymentTransactionStatus.SUCCESS, null, null, null, NOW))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(transaction.getStatus()).isEqualTo(PaymentTransactionStatus.REQUESTED);
        assertThat(transaction.getUpdatedAt()).isEqualTo(CREATED_AT);
    }
}
