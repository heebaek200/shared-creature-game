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

    @Column(name = "status", length = 30, nullable = false)
    private String status;

    @Column(name = "requested_at", nullable = false)
    private Instant requestedAt;

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
}
