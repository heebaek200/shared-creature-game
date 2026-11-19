package com.team.sharedcreature.payment.domain;

import com.team.sharedcreature.auth.domain.User;
import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import com.team.sharedcreature._core.exception.BusinessException;
import com.team.sharedcreature._core.exception.ErrorCode;

import java.util.Objects;
import java.time.Instant;

/**
 * refund_requests 테이블의 상태와 이력을 보존하는 JPA 엔티티입니다.
 */
@Getter
@Entity
@Table(name = "refund_requests", indexes = {
        @Index(name = "ix_refund_requests_status_requested", columnList = "status, requested_at"),
        @Index(name = "ix_refund_requests_purchase_created", columnList = "purchase_id, created_at")
})
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class RefundRequest {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "purchase_id", nullable = false)
    private Purchase purchase;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "requested_by_user_id", nullable = false)
    private User requestedByUser;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "reviewed_by_user_id")
    private User reviewedByUser;

    @Column(name = "request_reason", length = 500, nullable = false)
    private String requestReason;

    @Column(name = "decision_reason", length = 500)
    private String decisionReason;

    /** 환불 요청의 심사 및 PG 취소 처리 상태입니다. */
    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "status", length = 30, nullable = false)
    private RefundStatus status;

    @Column(name = "requested_at", nullable = false)
    private Instant requestedAt;

    @Column(name = "withdrawn_at")
    private Instant withdrawnAt;

    @Column(name = "reviewed_at")
    private Instant reviewedAt;

    @Column(name = "cancel_claimed_at")
    private Instant cancelClaimedAt;

    @Column(name = "completed_at")
    private Instant completedAt;

    @Column(
            name = "active_purchase_id",
            insertable = false,
            updatable = false,
            nullable = true
    )
    private Long activePurchaseId;

    @Version
    @Column(name = "version", nullable = false)
    private Long version;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    /**
     * 구매와 신청자를 연결하여 환불 요청을 생성합니다.
     * 구매·아이템·진행 중 환불 검증은 호출 Service의 잠금 안에서 수행합니다.
     *
     * @param purchase 환불 대상 구매
     * @param requester 환불을 신청한 사용자
     * @param reason 환불 요청 사유
     * @param now 요청 시각
     * @return REQUESTED 상태의 환불 요청
     */
    public static RefundRequest create(
            Purchase purchase,
            User requester,
            String reason,
            Instant now
    ) {
        Objects.requireNonNull(purchase, "purchase");
        Objects.requireNonNull(requester, "requester");
        Objects.requireNonNull(now, "now");

        String normalizedReason = normalizeReason(reason);

        RefundRequest refund = new RefundRequest();
        refund.purchase = purchase;
        refund.requestedByUser = requester;
        refund.requestReason = normalizedReason;
        refund.status = RefundStatus.REQUESTED;
        refund.requestedAt = now;
        refund.createdAt = now;
        refund.updatedAt = now;

        // 관리자 심사·PG 취소·환불 완료 정보는 아직 설정하지 않습니다.
        return refund;
    }

    /**
     * 관리자 처리 전인 본인 환불 요청을 철회합니다.
     * 이미 철회된 요청은 시각을 변경하지 않고 현재 결과를 재사용합니다.
     *
     * @param now 철회 시각
     * @return 새로 철회했으면 true, 이미 철회된 요청이면 false
     * @throws BusinessException 관리자 처리가 시작됐거나 다른 최종 상태인 경우
     */
    public boolean withdraw(Instant now) {
        Objects.requireNonNull(now, "now");

        if (status == RefundStatus.WITHDRAWN) {
            return false;
        }

        requireRequested();
        this.status = RefundStatus.WITHDRAWN;
        this.withdrawnAt = now;
        this.updatedAt = now;

        // 철회는 관리자 심사와 PG 취소를 시작하지 않습니다.
        return true;
    }

    /**
     * 관리자 승인을 기록하고 PG 취소 처리권을 선점합니다.
     * PG 호출은 이 변경이 Commit된 뒤 별도로 수행해야 합니다.
     *
     * @param reviewer 승인 관리자
     * @param reason 승인 사유
     * @param now 승인·선점 시각
     * @throws BusinessException 이미 심사되었거나 사유가 유효하지 않은 경우
     */
    public void claimCancellation(
            User reviewer,
            String reason,
            Instant now
    ) {
        Objects.requireNonNull(reviewer, "reviewer");
        Objects.requireNonNull(now, "now");

        requireRequested();
        String normalizedReason = normalizeReason(reason);

        // 모든 검증을 마친 뒤 심사 정보와 상태를 함께 변경합니다.
        this.reviewedByUser = reviewer;
        this.decisionReason = normalizedReason;
        this.reviewedAt = now;
        this.cancelClaimedAt = now;
        this.status = RefundStatus.CANCEL_PENDING;
        this.updatedAt = now;

        // PG 취소 성공 전이므로 completedAt과 아이템은 변경하지 않습니다.
    }

    /**
     * 아직 심사되지 않은 환불 요청을 관리자 판단으로 거절합니다.
     *
     * @param reviewer 거절 관리자
     * @param reason 거절 사유
     * @param now 거절 시각
     * @throws BusinessException 이미 심사되었거나 사유가 유효하지 않은 경우
     */
    public void reject(
            User reviewer,
            String reason,
            Instant now
    ) {
        Objects.requireNonNull(reviewer, "reviewer");
        Objects.requireNonNull(now, "now");

        requireRequested();
        String normalizedReason = normalizeReason(reason);

        this.reviewedByUser = reviewer;
        this.decisionReason = normalizedReason;
        this.reviewedAt = now;
        this.status = RefundStatus.REJECTED;
        this.updatedAt = now;

        // 거절은 PG 취소를 시작하지 않으며 환불 완료 시각도 기록하지 않습니다.
    }

    /**
     * PG에서 취소 성공이 확정된 환불을 완료합니다.
     *
     * @param completedAt PG에서 확인한 취소 완료 시각
     * @param now DB에 결과를 반영하는 서버 시각
     * @return 상태가 변경되었으면 true
     */
    public boolean completeCancellation(Instant completedAt, Instant now) {
        Objects.requireNonNull(completedAt, "completedAt");

        return applyCancellationState(RefundStatus.COMPLETED, completedAt, now);
    }

    /**
     * PG가 취소 실패를 명확하게 확정한 결과를 반영합니다.
     * Timeout이나 통신 장애에는 사용하지 않습니다.
     *
     * @param now 결과 반영 시각
     * @return 상태가 변경되었으면 true
     */
    public boolean failCancellation(Instant now) {
        return applyCancellationState(RefundStatus.FAILED, null, now);
    }

    /**
     * PG에서 취소가 아직 처리 중임을 확인한 결과를 반영합니다.
     *
     * @param now 결과 반영 시각
     * @return 상태가 변경되었으면 true
     */
    public boolean markCancellationPending(Instant now) {
        return applyCancellationState(RefundStatus.CANCEL_PENDING, null, now);
    }

    /**
     * 통신 장애 등으로 PG 취소 결과를 확정할 수 없는 상태를 기록합니다.
     *
     * @param now 결과 반영 시각
     * @return 상태가 변경되었으면 true
     */
    public boolean markCancellationUnknown(Instant now) {
        return applyCancellationState(RefundStatus.CANCEL_UNKNOWN, null, now);
    }

    public boolean tryClaimRecovery(Instant now) {
        // Scheduler는 기존과 동일하게 5분 경과를 요구합니다.
        return tryClaimRecovery(now, false);
    }

    /**
     * 호출 목적에 따라 미확정 환불의 복구 처리권을 선점합니다.
     * 호출자는 DB 잠금과 진행 중인 조회 이력 검사를 먼저 수행해야 합니다.
     *
     * @param now 잠금 획득 후 읽은 현재 시각
     * @param allowImmediateUnknown 관리자 수동 요청의 결과 불명 즉시 조회 허용 여부
     * @return 처리권을 선점했으면 true
     */
    public boolean tryClaimRecovery(
            Instant now,
            boolean allowImmediateUnknown
    ) {
        Objects.requireNonNull(now, "now");

        if (status != RefundStatus.CANCEL_PENDING
                && status != RefundStatus.CANCEL_UNKNOWN) {
            return false;
        }

        // 선점 이력이 없거나 현재보다 미래 시각이면 처리하지 않습니다.
        if (cancelClaimedAt == null || cancelClaimedAt.isAfter(now)) {
            return false;
        }

        boolean immediateUnknown = allowImmediateUnknown
                && status == RefundStatus.CANCEL_UNKNOWN;

        Instant cutoff = now.minusSeconds(300);

        // 관리자 결과 불명 재조회 외에는 정확히 5분 경과를 요구합니다.
        if (!immediateUnknown && cancelClaimedAt.isAfter(cutoff)) {
            return false;
        }

        // 조회 준비 단계이므로 상태·심사 사유·완료 시각은 유지합니다.
        this.cancelClaimedAt = now;
        this.updatedAt = now;

        return true;
    }

    /**
     * 진행 중인 취소에만 PG 결과를 반영합니다.
     * 이미 확정된 결과와 동일 상태의 반복 반영은 변경 없이 종료합니다.
     *
     * @param nextStatus 반영할 환불 상태
     * @param completedAt 취소 성공 시의 PG 완료 시각, 나머지는 null
     * @param now 결과 반영 시각
     * @return 상태가 변경되었으면 true
     * @throws BusinessException 취소가 시작되지 않은 요청에 결과를 반영한 경우
     */
    private boolean applyCancellationState(
            RefundStatus nextStatus,
            Instant completedAt,
            Instant now
    ) {
        Objects.requireNonNull(now, "now");

        // 늦게 도착한 응답으로 확정된 성공·실패를 덮어쓰지 않습니다.
        if (status == RefundStatus.COMPLETED || status == RefundStatus.FAILED) {
            return false;
        }

        if (status != RefundStatus.CANCEL_PENDING
                && status != RefundStatus.CANCEL_UNKNOWN) {
            throw new BusinessException(
                    ErrorCode.CONCURRENT_MODIFICATION,
                    "취소 처리 중인 환불 요청에만 PG 결과를 반영할 수 있습니다."
            );
        }

        // 동일 결과의 반복 조회로 상태와 변경 시각을 다시 기록하지 않습니다.
        if (nextStatus == status) {
            return false;
        }

        this.status = nextStatus;
        this.updatedAt = now;

        // 관리자 승인 시각과 실제 PG 취소 완료 시각은 구분합니다.
        if (nextStatus == RefundStatus.COMPLETED) {
            this.completedAt = completedAt;
        }

        return true;
    }

    /**
     * 관리자 최초 심사는 REQUESTED 상태에서만 허용합니다.
     *
     * @throws BusinessException 다른 요청이 이미 심사를 진행하거나 확정한 경우
     */
    private void requireRequested() {
        if (status != RefundStatus.REQUESTED) {
            throw new BusinessException(
                    ErrorCode.CONCURRENT_MODIFICATION,
                    "이미 처리 중이거나 심사가 완료된 환불 요청입니다."
            );
        }
    }

    /**
     * 요청·판단 사유의 앞뒤 공백을 제거하고 저장 가능한 길이를 검사합니다.
     *
     * @param reason 검증할 사유
     * @return 앞뒤 공백이 제거된 사유
     * @throws BusinessException 사유가 없거나 trim 후 500자를 초과한 경우
     */
    private static String normalizeReason(String reason) {
        if (reason == null) {
            throw new BusinessException(
                    ErrorCode.INVALID_FIELD_VALUE,
                    "사유는 필수입니다."
            );
        }

        String normalized = reason.trim();

        if (normalized.isBlank() || normalized.length() > 500) {
            throw new BusinessException(
                    ErrorCode.INVALID_FIELD_VALUE,
                    "사유는 앞뒤 공백 제거 후 1자 이상 500자 이하여야 합니다."
            );
        }

        return normalized;
    }
}
