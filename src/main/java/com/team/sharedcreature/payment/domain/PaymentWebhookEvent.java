package com.team.sharedcreature.payment.domain;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * payment_webhook_events 테이블의 상태와 이력을 보존하는 JPA 엔티티입니다.
 */
@Getter
@Entity
@Table(name = "payment_webhook_events", uniqueConstraints =
        @UniqueConstraint(name = "uq_payment_webhook_events_provider_event", columnNames = {"provider", "provider_event_id"}), indexes = {
        @Index(name = "ix_webhook_events_provider_payment", columnList = "provider, provider_payment_id"),
        @Index(name = "ix_webhook_events_processing_started", columnList = "processing_status, processing_started_at")
})
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class PaymentWebhookEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "provider", length = 30, nullable = false)
    private String provider;

    @Column(name = "provider_event_id", length = 255, nullable = false)
    private String providerEventId;

    @Column(name = "provider_payment_id", length = 100)
    private String providerPaymentId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "purchase_id")
    private Purchase purchase;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "payment_transaction_id", nullable = true)
    private PaymentTransaction paymentTransaction;

    @Column(name = "event_type", length = 100, nullable = false)
    private String eventType;

    @Column(name = "payload_hash", length = 64, nullable = false)
    private String payloadHash;

    @Column(name = "payload_summary", columnDefinition = "JSON", nullable = false)
    private String payloadSummary;

    @Column(name = "processing_status", length = 30, nullable = false)
    private String processingStatus;

    @Column(name = "failure_detail", length = 1000)
    private String failureDetail;

    @Column(name = "received_at", nullable = false)
    private Instant receivedAt;

    @Column(name = "processing_started_at")
    private Instant processingStartedAt;

    @Column(name = "attempt_count", nullable = false)
    private Integer attemptCount;

    @Column(name = "processed_at")
    private Instant processedAt;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;
}
