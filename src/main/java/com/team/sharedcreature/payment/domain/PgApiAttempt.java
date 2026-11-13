package com.team.sharedcreature.payment.domain;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.UUID;
import java.util.Set;

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

    /**
     * 승인 선점된 환불의 PG 취소 호출 준비 이력을 생성합니다.
     * 환불 상태 변경과 같은 트랜잭션에서 저장해야 합니다.
     *
     * @param refund CANCEL_PENDING으로 선점된 환불 요청
     * @param attemptNumber 취소 호출 시도 번호, 최초는 1
     * @param now 호출 준비 시각
     * @return 아직 결과가 확정되지 않은 PG 시도 이력
     */
    public static PgApiAttempt prepareCancellation(
            RefundRequest refund,
            int attemptNumber,
            Instant now
    ) {
        Objects.requireNonNull(refund, "refund");
        Objects.requireNonNull(now, "now");

        Long refundId = refund.getId();

        if (refundId == null || refundId < 1 || attemptNumber < 1) {
            throw new IllegalArgumentException(
                    "저장된 환불 요청과 1 이상의 시도 번호가 필요합니다."
            );
        }

        if (!"CANCEL_PENDING".equals(refund.getStatus())) {
            throw new IllegalStateException(
                    "취소 처리권을 선점한 환불만 PG 호출을 준비할 수 있습니다."
            );
        }

        // 관리자 요청 키와 구분하여 환불 요청별로 일정한 PG 멱등 키를 만듭니다.
        String keySource = "refund-cancellation:" + refundId;
        String pgIdempotencyKey = UUID.nameUUIDFromBytes(
                keySource.getBytes(StandardCharsets.UTF_8)
        ).toString();

        PgApiAttempt attempt = new PgApiAttempt();
        attempt.purchase = Objects.requireNonNull(
                refund.getPurchase(),
                "refund.purchase"
        );
        attempt.refundRequest = refund;
        attempt.operation = "CANCELLATION";
        attempt.idempotencyKey = pgIdempotencyKey;
        attempt.attemptNumber = attemptNumber;
        attempt.resultType = "STARTED";
        attempt.startedAt = now;
        attempt.createdAt = now;

        // PG 응답을 받기 전이므로 결과·완료 시각은 설정하지 않습니다.
        return attempt;
    }

    /**
     * 한 번의 PG 호출에서 확인한 결과를 기록합니다.
     * 이미 기록된 호출 결과는 복구 조회 결과로 덮어쓰지 않습니다.
     *
     * @param resultType SUCCESS·DEFINITIVE_FAILURE·PENDING·UNKNOWN 중 하나
     * @param httpStatus PG HTTP 응답 상태, 응답이 없으면 null
     * @param providerResultCode PG 결과 코드, 없으면 null
     * @param responseSummary 민감정보를 제외하고 직렬화한 JSON 요약
     * @param transaction 연결할 취소 거래, 없으면 null
     * @param now 호출 결과를 기록하는 서버 시각
     * @return 처음 기록했으면 true, 이미 기록되어 있으면 false
     */
    public boolean complete(
            String resultType,
            Integer httpStatus,
            String providerResultCode,
            String responseSummary,
            PaymentTransaction transaction,
            Instant now
    ) {
        Objects.requireNonNull(now, "now");

        // 같은 호출의 결과를 반복 저장해도 최초 이력을 유지합니다.
        if (this.completedAt != null) {
            return false;
        }

        if (resultType == null || !Set.of(
                "SUCCESS",
                "DEFINITIVE_FAILURE",
                "PENDING",
                "UNKNOWN"
        ).contains(resultType)) {
            throw new IllegalArgumentException("유효하지 않은 PG 호출 결과입니다.");
        }

        if (httpStatus != null && (httpStatus < 100 || httpStatus > 599)) {
            throw new IllegalArgumentException("유효하지 않은 PG HTTP 상태입니다.");
        }

        if (providerResultCode != null && providerResultCode.length() > 100) {
            throw new IllegalArgumentException("PG 결과 코드는 100자 이하여야 합니다.");
        }

        if (transaction != null) {
            // 다른 구매의 거래나 승인 거래를 취소 시도에 연결하지 않습니다.
            if (!Objects.equals(
                    purchase.getId(),
                    transaction.getPurchase().getId()
            ) || !PaymentTransactionType.CANCELLATION.name()
                    .equals(transaction.getTransactionType())) {
                throw new IllegalArgumentException(
                        "동일 구매의 취소 거래만 연결할 수 있습니다."
                );
            }
        }

        // 모든 검증을 통과한 뒤 결과를 함께 기록합니다.
        this.resultType = resultType;
        this.httpStatus = httpStatus;
        this.providerResultCode = providerResultCode;
        this.responseSummary = responseSummary;
        this.paymentTransaction = transaction;
        this.completedAt = now;

        return true;
    }
}
