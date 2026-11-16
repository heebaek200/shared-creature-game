package com.team.sharedcreature.payment.domain;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.Objects;

/**
 * payment_transactions 테이블의 상태와 이력을 보존하는 JPA 엔티티입니다.
 */
@Getter
@Entity
@Table(name = "payment_transactions", uniqueConstraints =
        @UniqueConstraint(name = "uq_payment_transactions_provider_transaction", columnNames = {"provider", "provider_transaction_id"}), indexes = {
        @Index(name = "ix_payment_transactions_purchase_type_created", columnList = "purchase_id, transaction_type, created_at"),
        @Index(name = "ix_payment_transactions_status_created", columnList = "status, created_at")
})
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class PaymentTransaction {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "purchase_id", nullable = false)
    private Purchase purchase;

    @Column(name = "provider", length = 30, nullable = false)
    private String provider;

    @Column(name = "provider_transaction_id", length = 255)
    private String providerTransactionId;

    /** 결제 승인 거래와 환불 취소 거래를 구분합니다. */
    @Enumerated(EnumType.STRING)
    @Column(name = "transaction_type", length = 30, nullable = false)
    private PaymentTransactionType transactionType;

    @Column(name = "amount_krw", nullable = false)
    private Long amountKrw;

    /** 승인·취소 거래의 처리 결과입니다. */
    @Enumerated(EnumType.STRING)
    @Column(name = "status", length = 30, nullable = false)
    private PaymentTransactionStatus status;

    @Column(name = "provider_result_code", length = 100)
    private String providerResultCode;

    @Column(name = "processed_at")
    private Instant processedAt;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    private PaymentTransaction(
            Purchase purchase,
            String providerTransactionId,
            Long amountKrw,
            PaymentTransactionStatus status,
            Instant processedAt,
            Instant now
    ) {
        this.purchase = purchase;
        this.provider = "PORTONE";
        this.providerTransactionId = providerTransactionId;
        this.transactionType = PaymentTransactionType.APPROVAL;
        this.amountKrw = amountKrw;
        this.status = status;
        this.processedAt = processedAt;
        this.createdAt = now;
        this.updatedAt = now;
    }

    /** 검증된 PortOne 승인 거래를 생성합니다. */
    public static PaymentTransaction approved(
            Purchase purchase,
            String providerTransactionId,
            Long amountKrw,
            Instant processedAt,
            Instant now
    ) {
        return new PaymentTransaction(
                purchase,
                providerTransactionId,
                amountKrw,
                PaymentTransactionStatus.SUCCESS,
                processedAt,
                now
        );
    }

    /** 공급자가 명확히 실패로 확정한 승인 시도를 생성합니다. */
    public static PaymentTransaction failed(Purchase purchase, Long amountKrw, Instant now) {
        return new PaymentTransaction(
                purchase,
                null,
                amountKrw,
                PaymentTransactionStatus.DEFINITIVE_FAILURE,
                now,
                now
        );
    }

    /** 공급자 조회 결과를 확정할 수 없는 승인 시도를 현재 미확정 이력으로 생성합니다. */
    public static PaymentTransaction unknown(Purchase purchase, Long amountKrw, Instant now) {
        return new PaymentTransaction(
                purchase,
                null,
                amountKrw,
                PaymentTransactionStatus.UNKNOWN,
                null,
                now
        );
    }

    /**
     * 환불을 위한 전체 취소 거래를 미확정 상태로 생성합니다.
     * PG 취소 성공을 의미하지 않으며 결과 확인 후 별도로 갱신합니다.
     *
     * @param purchase 환불 대상 구매
     * @param now 거래 이력 생성 시각
     * @return CANCELLATION 유형의 REQUESTED 거래
     */
    public static PaymentTransaction cancellationRequested(
            Purchase purchase,
            Instant now
    ) {
        Objects.requireNonNull(purchase, "purchase");
        Objects.requireNonNull(now, "now");

        Long amount = purchase.getPriceKrwSnapshot();

        if (amount == null || amount < 1) {
            throw new IllegalArgumentException(
                    "전체 취소 거래에는 유효한 구매 금액이 필요합니다."
            );
        }

        PaymentTransaction transaction = new PaymentTransaction();
        transaction.purchase = purchase;
        transaction.provider = "PORTONE";
        transaction.transactionType = PaymentTransactionType.CANCELLATION;
        transaction.amountKrw = amount;
        transaction.status = PaymentTransactionStatus.REQUESTED;
        transaction.createdAt = now;
        transaction.updatedAt = now;

        // PG 취소 거래 ID와 처리 시각은 확인 전까지 null로 유지합니다.
        return transaction;
    }

    /**
     * 미확정 취소 거래에 검증된 PG 결과를 반영합니다.
     * 호출자는 DB 잠금과 결제 ID·전체 취소 금액 검증을 완료해야 합니다.
     *
     * @param nextStatus 반영할 취소 거래 상태
     * @param providerTransactionId PG 취소 거래 ID, 미확정이면 null
     * @param processedAt PG 처리 시각, 미확정이면 null
     * @param providerResultCode PG 결과 코드, 없으면 null
     * @param now 결과를 반영하는 서버 시각
     * @return 실제 변경되었으면 true
     */
    public boolean applyCancellationResult(
            PaymentTransactionStatus nextStatus,
            String providerTransactionId,
            Instant processedAt,
            String providerResultCode,
            Instant now
    ) {
        Objects.requireNonNull(nextStatus, "nextStatus");
        Objects.requireNonNull(now, "now");

        // 기존 승인 거래를 취소 결과로 변경하지 않습니다.
        if (transactionType != PaymentTransactionType.CANCELLATION) {
            throw new IllegalStateException(
                    "취소 거래에만 취소 결과를 반영할 수 있습니다."
            );
        }

        // 늦게 도착한 응답으로 확정 결과를 변경하지 않습니다.
        if (status == PaymentTransactionStatus.SUCCESS
                || status == PaymentTransactionStatus.DEFINITIVE_FAILURE) {
            return false;
        }

        if (status != PaymentTransactionStatus.REQUESTED
                && status != PaymentTransactionStatus.UNKNOWN) {
            throw new IllegalStateException("유효하지 않은 취소 거래 상태입니다.");
        }

        if (providerTransactionId != null
                && (providerTransactionId.isBlank()
                || providerTransactionId.length() > 255)) {
            throw new IllegalArgumentException("유효하지 않은 PG 취소 거래 ID입니다.");
        }

        // 한 번 연결된 거래를 다른 PG 취소 거래로 바꾸지 않습니다.
        if (this.providerTransactionId != null
                && providerTransactionId != null
                && !this.providerTransactionId.equals(providerTransactionId)) {
            throw new IllegalStateException("PG 취소 거래 ID가 일치하지 않습니다.");
        }

        String resolvedTransactionId = providerTransactionId == null
                ? this.providerTransactionId
                : providerTransactionId;

        if (nextStatus == PaymentTransactionStatus.SUCCESS
                && (resolvedTransactionId == null || processedAt == null)) {
            throw new IllegalArgumentException(
                    "취소 성공에는 PG 취소 거래 ID와 처리 시각이 필요합니다."
            );
        }

        boolean unresolved = nextStatus == PaymentTransactionStatus.REQUESTED
                || nextStatus == PaymentTransactionStatus.UNKNOWN;

        if (unresolved && processedAt != null) {
            throw new IllegalArgumentException(
                    "미확정 취소 거래에는 처리 완료 시각을 기록할 수 없습니다."
            );
        }

        if (providerResultCode != null && providerResultCode.length() > 100) {
            throw new IllegalArgumentException("PG 결과 코드는 100자 이하여야 합니다.");
        }

        // 동일한 결과를 반복 반영해도 변경 시각을 유지합니다.
        if (nextStatus == this.status
                && Objects.equals(this.providerTransactionId, resolvedTransactionId)
                && Objects.equals(this.processedAt, processedAt)
                && Objects.equals(this.providerResultCode, providerResultCode)) {
            return false;
        }

        this.status = nextStatus;
        this.providerTransactionId = resolvedTransactionId;
        this.processedAt = processedAt;
        this.providerResultCode = providerResultCode;
        this.updatedAt = now;

        return true;
    }
}
