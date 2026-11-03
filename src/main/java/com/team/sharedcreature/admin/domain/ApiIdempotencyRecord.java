package com.team.sharedcreature.admin.domain;

import com.team.sharedcreature.auth.domain.User;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * api_idempotency_records 테이블의 상태와 이력을 보존하는 JPA 엔티티입니다.
 */
@Getter
@Entity
@Table(name = "api_idempotency_records", uniqueConstraints =
        @UniqueConstraint(name = "uq_api_idempotency_actor_operation_key", columnNames = {"actor_user_id", "operation", "idempotency_key"}))
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ApiIdempotencyRecord {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "actor_user_id", nullable = false)
    private User actorUser;

    @Column(name = "operation", length = 50, nullable = false)
    private String operation;

    @Column(name = "idempotency_key", length = 36, nullable = false)
    private String idempotencyKey;

    @Column(name = "request_hash", length = 64, nullable = false)
    private String requestHash;

    @Column(name = "status", length = 30, nullable = false)
    private String status;

    @Column(name = "http_status")
    private Integer httpStatus;

    @Column(name = "resource_type", length = 50)
    private String resourceType;

    @Column(name = "resource_id")
    private Long resourceId;

    @Column(name = "response_snapshot", columnDefinition = "JSON")
    private String responseSnapshot;

    @Column(name = "completed_at")
    private Instant completedAt;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;
}
