package com.team.sharedcreature.payment.domain;

import com.team.sharedcreature.auth.domain.User;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * refund_requests 테이블의 상태와 이력을 보존하는 JPA 엔티티입니다.
 */
@Getter
@Entity
@Table(name = "refund_requests", uniqueConstraints =
        @UniqueConstraint(name = "uq_refund_requests_active_purchase", columnNames = "active_purchase_id"))
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
    @JoinColumn(name = "reviewed_by_user_id", nullable = false)
    private User reviewedByUser;

    @Column(name = "request_reason", length = 500, nullable = false)
    private String requestReason;

    @Column(name = "decision_reason", length = 500, nullable = false)
    private String decisionReason;

    @Column(name = "status", length = 30, nullable = false)
    private String status;

    @Column(name = "requested_at", nullable = false)
    private Instant requestedAt;

    @Column(name = "reviewed_at", nullable = false)
    private Instant reviewedAt;

    @Column(name = "cancel_claimed_at", nullable = false)
    private Instant cancelClaimedAt;

    @Column(name = "completed_at", nullable = false)
    private Instant completedAt;

    @Column(
            name = "active_purchase_id",
            columnDefinition = "BIGINT GENERATED ALWAYS AS (CASE WHEN status IN ('REQUESTED', 'CANCEL_PENDING', 'CANCEL_UNKNOWN') THEN purchase_id ELSE NULL END) STORED",
            insertable = false,
            updatable = false
    )
    private Long activePurchaseId;

    @Version
    @Column(name = "version", nullable = false)
    private Long version;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;
}
