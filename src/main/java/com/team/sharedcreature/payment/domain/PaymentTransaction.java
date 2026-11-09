package com.team.sharedcreature.payment.domain;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;

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

    @Column(name = "transaction_type", length = 30, nullable = false)
    private String transactionType;

    @Column(name = "amount_krw", nullable = false)
    private Long amountKrw;

    @Column(name = "status", length = 30, nullable = false)
    private String status;

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
            String status,
            Instant processedAt,
            Instant now
    ) {
        this.purchase = purchase;
        this.provider = "PORTONE";
        this.providerTransactionId = providerTransactionId;
        this.transactionType = PaymentTransactionType.APPROVAL.name();
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
                PaymentTransactionStatus.SUCCESS.name(),
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
                PaymentTransactionStatus.DEFINITIVE_FAILURE.name(),
                now,
                now
        );
    }
}
