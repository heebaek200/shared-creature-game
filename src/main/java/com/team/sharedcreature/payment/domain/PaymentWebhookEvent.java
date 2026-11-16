package com.team.sharedcreature.payment.domain;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * payment_webhook_events 테이블의 상태와 이력을 보존하는 JPA 엔티티입니다.
 * 원본 이벤트 ID와 실패 시각을 유지해 복구 대상을 식별합니다.
 * #24 스케줄 복구는 마지막 처리 실패 후 5분이 경과한 경우만 재시도합니다.
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

    /**
     * 검증을 통과한 공급자 이벤트의 최초 접수 상태를 생성합니다.
     * PENDING의 처리 시작 시각은 접수 시각으로 기록합니다.
     * 구매 ID가 아직 없는 미매칭 Webhook도 보존할 수 있습니다.
     */
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

    /**
     * 서명 검증을 통과한 Webhook의 최소 이력을 생성합니다.
     * 이벤트 식별자와 원장 연결 정보를 보존합니다.
     * Scheduler에서 별도의 접수 이력을 다시 만들지 않습니다.
     */
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
                providerEventId, providerPaymentId, purchase, eventType,
                payloadHash, payloadSummary, now);
    }

    /**
     * 지원 대상이 아닌 이벤트를 IGNORED로 확정합니다.
     * 이 경우 복구 Scheduler의 후보에서 제외됩니다.
     * 최초 이벤트 ID와 Payload 요약은 보존합니다.
     */
    public void ignore(Instant now) {
        processingStatus = WebhookProcessingStatus.IGNORED.name();
        processedAt = now;
        updatedAt = now;
    }

    /**
     * 최신 결제 상태의 정합화가 완료되면 처리 이력을 확정합니다.
     * 동일 이벤트의 복구 대상 선정을 종료합니다.
     * 기존 원장과 구매 연결을 변경하지 않고 재사용합니다.
     */
    public void complete(Purchase purchase, PaymentTransaction transaction, Instant now) {
        this.purchase = purchase;
        paymentTransaction = transaction;
        processingStatus = WebhookProcessingStatus.PROCESSED.name();
        failureDetail = null;
        processedAt = now;
        updatedAt = now;
    }

    /**
     * 기술적 또는 업무적 실패를 기록하고 다음 복구 대기 시간을 시작합니다.
     * updatedAt은 마지막 실패 확정 시각이므로 5분 복구 시각 계산에 사용합니다.
     * 원본 Webhook ID와 실패 사유는 삭제하지 않습니다.
     */
    public void fail(String detail, Instant now) {
        processingStatus = WebhookProcessingStatus.FAILED.name();
        failureDetail = detail == null ? null : detail.substring(0, Math.min(detail.length(), 1000));
        processedAt = now;
        updatedAt = now;
    }

    /**
     * 재조회 후에도 Webhook이 5분 이상 미처리 상태인지 확인합니다.
     * FAILED는 마지막 실패 확정(updatedAt) 이후 5분을 기다립니다.
     * PENDING은 처리 시작(processingStartedAt) 이후 5분을 기다립니다.
     */
    public boolean isRecoverable(Instant staleBefore) {
        // 쿼리의 필터와 같은 조건을 적용해 행 잠금 뒤 상태 경합을 방어합니다.
        if (WebhookProcessingStatus.FAILED.name().equals(processingStatus)) {
            return updatedAt != null && !updatedAt.isAfter(staleBefore);
        }
        return WebhookProcessingStatus.PENDING.name().equals(processingStatus)
                && processingStartedAt != null
                && !processingStartedAt.isAfter(staleBefore);
    }

    /**
     * 현재 복구 시도를 시작하고 PENDING으로 전환합니다.
     * processingStartedAt을 갱신하여 다른 실행의 5분 고착 판정을 방어합니다.
     * 다음 시도에서 이전 결과와 구분하도록 attemptCount를 증가시킵니다.
     */
    public void retry(Instant now) {
        processingStatus = WebhookProcessingStatus.PENDING.name();
        processingStartedAt = now;
        attemptCount++;
        processedAt = null;
        updatedAt = now;
    }
}
