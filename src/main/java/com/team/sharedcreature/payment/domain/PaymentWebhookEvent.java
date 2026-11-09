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

    private PaymentWebhookEvent(
            String providerEventId,
            String providerPaymentId,
            Purchase purchase,
            String eventType,
            String payloadHash,
            String payloadSummary,
            Instant now
    ) {
        this.provider = "PORTONE";
        this.providerEventId = providerEventId;
        this.providerPaymentId = providerPaymentId;
        this.purchase = purchase;
        this.eventType = eventType;
        this.payloadHash = payloadHash;
        this.payloadSummary = payloadSummary;
        this.processingStatus = WebhookProcessingStatus.PENDING.name();
        this.receivedAt = now;
        this.processingStartedAt = now;
        this.attemptCount = 1;
        this.createdAt = now;
        this.updatedAt = now;
    }

    /** 서명 검증을 통과한 PortOne 이벤트를 원문 대신 최소 요약으로 보존합니다. */
    public static PaymentWebhookEvent received(
            String providerEventId,
            String providerPaymentId,
            Purchase purchase,
            String eventType,
            String payloadHash,
            String payloadSummary,
            Instant now
    ) {
        return new PaymentWebhookEvent(
                providerEventId,
                providerPaymentId,
                purchase,
                eventType,
                payloadHash,
                payloadSummary,
                now
        );
    }

    /** 지원하지 않는 공급자 이벤트를 안전하게 무시 상태로 종료합니다. */
    public void ignore(Instant now) {
        processingStatus = WebhookProcessingStatus.IGNORED.name();
        processedAt = now;
        updatedAt = now;
    }

    /** 최신 결제 상태의 정합화가 끝난 이벤트를 완료 처리합니다. */
    public void complete(PaymentTransaction transaction, Instant now) {
        paymentTransaction = transaction;
        processingStatus = WebhookProcessingStatus.PROCESSED.name();
        failureDetail = null;
        processedAt = now;
        updatedAt = now;
    }

    /** 재처리 가능한 실패 원인을 민감정보 없이 기록합니다. */
    public void fail(String detail, Instant now) {
        processingStatus = WebhookProcessingStatus.FAILED.name();
        failureDetail = detail == null ? null : detail.substring(0, Math.min(detail.length(), 1000));
        processedAt = now;
        updatedAt = now;
    }

    /** 고착 이벤트를 다시 처리하도록 선점합니다. */
    public void retry(Instant now) {
        processingStatus = WebhookProcessingStatus.PENDING.name();
        processingStartedAt = now;
        attemptCount++;
        processedAt = null;
        updatedAt = now;
    }
}
