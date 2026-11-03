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
@Table(name = "payment_transactions")
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

    @Column(name = "provider_transaction_id", length = 255, nullable = false)
    private String providerTransactionId;

    @Column(name = "transaction_type", length = 30, nullable = false)
    private String transactionType;

    @Column(name = "amount_krw", nullable = false)
    private Long amountKrw;

    @Column(name = "status", length = 30, nullable = false)
    private String status;

    @Column(name = "provider_result_code", length = 100, nullable = false)
    private String providerResultCode;

    @Column(name = "processed_at", nullable = false)
    private Instant processedAt;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;
}
