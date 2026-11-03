package com.team.sharedcreature.admin.domain;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * scheduler_executions 테이블의 상태와 이력을 보존하는 JPA 엔티티입니다.
 */
@Getter
@Entity
@Table(name = "scheduler_executions", uniqueConstraints =
        @UniqueConstraint(name = "uq_scheduler_executions_job_scheduled", columnNames = {"job_type", "scheduled_for"}), indexes = {
        @Index(name = "ix_scheduler_executions_status_lease", columnList = "status, lease_expires_at"),
        @Index(name = "ix_scheduler_executions_status_completed", columnList = "status, completed_at")
})
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class SchedulerExecution {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "job_type", length = 50, nullable = false)
    private String jobType;

    @Column(name = "scheduled_for", nullable = false)
    private Instant scheduledFor;

    @Column(name = "status", length = 30, nullable = false)
    private String status;

    @Column(name = "attempt_count", nullable = false)
    private Integer attemptCount;

    @Column(name = "owner_instance_id", length = 100, nullable = false)
    private String ownerInstanceId;

    @Column(name = "started_at", nullable = false)
    private Instant startedAt;

    @Column(name = "heartbeat_at", nullable = false)
    private Instant heartbeatAt;

    @Column(name = "lease_expires_at", nullable = false)
    private Instant leaseExpiresAt;

    @Column(name = "completed_at")
    private Instant completedAt;

    @Column(name = "result_summary", columnDefinition = "JSON")
    private String resultSummary;

    @Column(name = "error_code", length = 100)
    private String errorCode;

    @Column(name = "error_detail", length = 1000)
    private String errorDetail;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;
}
