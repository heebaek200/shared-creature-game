package com.team.sharedcreature.payment.domain;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * pg_api_attempts 테이블의 상태와 이력을 보존하는 JPA 엔티티입니다.
 */
@Getter
@Entity
@Table(name = "pg_api_attempts", uniqueConstraints =
        @UniqueConstraint(name = "uq_pg_api_attempts_key_attempt", columnNames = {"idempotency_key", "attempt_number"}), indexes = {
        @Index(name = "ix_pg_api_attempts_result_created", columnList = "result_type, created_at"),
        @Index(name = "ix_pg_api_attempts_refund_created", columnList = "refund_request_id, created_at")
})
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class PgApiAttempt {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "purchase_id", nullable = false)
    private Purchase purchase;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "payment_transaction_id", nullable = true)
    private PaymentTransaction paymentTransaction;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "refund_request_id", nullable = true)
    private RefundRequest refundRequest;

    @Column(name = "operation", length = 30, nullable = false)
    private String operation;

    @Column(name = "idempotency_key", length = 128, nullable = false)
    private String idempotencyKey;

    @Column(name = "attempt_number", nullable = false)
    private Integer attemptNumber;

    @Column(name = "result_type", length = 30, nullable = false)
    private String resultType;

    @Column(name = "http_status")
    private Integer httpStatus;

    @Column(name = "provider_result_code", length = 100)
    private String providerResultCode;

    @Column(name = "response_summary", columnDefinition = "JSON")
    private String responseSummary;

    @Column(name = "started_at", nullable = false)
    private Instant startedAt;

    @Column(name = "completed_at")
    private Instant completedAt;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;
}
