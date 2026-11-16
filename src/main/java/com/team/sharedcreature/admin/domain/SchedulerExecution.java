package com.team.sharedcreature.admin.domain;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

/**
 * scheduler_executions 테이블의 상태와 이력을 보존하는 JPA 엔티티입니다.
 * 동일한 jobType과 scheduledFor 조합은 한 행으로 관리합니다.
 * Lease와 시도 번호를 이용해 중복 실행 및 오래된 실행자의 갱신을 방지합니다.
 */
@Getter
@Entity
@Table(
        name = "scheduler_executions",
        uniqueConstraints =
                @UniqueConstraint(name = "uq_scheduler_executions_job_scheduled", columnNames = {"job_type", "scheduled_for"}),
        indexes = {
                @Index(name = "ix_scheduler_executions_status_lease", columnList = "status, lease_expires_at"),
                @Index(name = "ix_scheduler_executions_status_completed", columnList = "status, completed_at")
        }
)
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

    /**
     * 새로운 Scheduler 실행 행을 생성하는 팩토리
     *  - 첫 실행은 RUNNING 상태와 시도 번호 1로 시작함
     *  - 실제 중복 선점 방지는 Repository의 UNIQUE 제약으로 수행함
     */
    public static SchedulerExecution startNew(
            String jobType,
            Instant scheduledFor,
            String ownerInstanceId,
            Instant now,
            Duration leaseDuration
    ) {
        validateLeaseDuration(leaseDuration);
        Objects.requireNonNull(scheduledFor, "scheduledFor");
        Objects.requireNonNull(now, "now");

        if (jobType == null || jobType.isBlank()) {
            throw new IllegalArgumentException("jobType은 필수입니다.");
        }
        if (ownerInstanceId == null || ownerInstanceId.isBlank()) {
            throw new IllegalArgumentException("ownerInstanceId는 필수입니다.");
        }

        // 최초 실행 정보 설정
        SchedulerExecution execution = new SchedulerExecution();
        execution.jobType      = jobType;
        execution.scheduledFor = scheduledFor;
        execution.attemptCount = 1;
        execution.createdAt    = now;
        execution.startAttempt(ownerInstanceId, now, leaseDuration);

        return execution;
    }

    /**
     * 현재 실행권을 다시 선점할 수 있는지 판정한다.
     * FAILED : 재시도 가능
     * RUNNING : 은 Lease가 만료되면 가능
     * SUCCEEDED : 재선점 불가
     */
    public boolean canReclaim(Instant now) {
        Objects.requireNonNull(now, "now");

        if ("FAILED".equals(status)) {
            return true;
        }

        return "RUNNING".equals(status) && !now.isBefore(leaseExpiresAt);
    }

    /**
     * FAILED 또는 Lease가 만료된 RUNNING에 대해 재선점.
     * 시도 번호를 증가시키며, 임시 상태를 초기화한다.
     */
    public void reclaim(
            String ownerInstanceId,
            Instant now,
            Duration leaseDuration
    ) {
        if (! canReclaim(now)) {
            throw new IllegalStateException("실행을 재선점할 수 없습니다.");
        }

        validateLeaseDuration(leaseDuration);
        if (ownerInstanceId == null || ownerInstanceId.isBlank()) {
            throw new IllegalArgumentException("ownerInstanceId는 필수입니다.");
        }

        // 이전 실행자의 요청과 구분하는 시도 번호 갱신
        attemptCount = Math.addExact(attemptCount, 1);
        startAttempt(ownerInstanceId, now, leaseDuration);
    }

    /**
     * 실행 중인 작업의 Lease를 연장시킨다.
     * 현재 실행자와 시도 번호가 일치해야하며 Lease가 만료되지 않아야 한다.
     */
    public void renewLease(
            String ownerInstanceId,
            int expectedAttemptCount,
            Instant now,
            Duration leaseDuration
    ) {
        requireActiveAttempt(ownerInstanceId, expectedAttemptCount, now);
        validateLeaseDuration(leaseDuration);

        heartbeatAt = now;
        leaseExpiresAt = now.plus(leaseDuration);
        updatedAt = now;
    }

    /**
     * 현재 실행이 성공적으로 완료되었음을 기록함
     *  - 실제 업무 트랜잭션의 Commit 이후에 호출해야 함
     */
    public void markSucceeded(
            String ownerInstanceId,
            int expectedAttemptCount,
            Instant now,
            String resultSummary
    ) {
        requireActiveAttempt(ownerInstanceId, expectedAttemptCount, now);

        status = "SUCCEEDED";
        completedAt = now;
        this.resultSummary = resultSummary;
        updatedAt = now;
    }

    /**
     * 현재 실행이 실패 발생했음을 기록함
     *  - 이력은 삭제하지 않고 다음 재시도에 활용함
     *  - 선점권을 잃은 실행자는 실패 상태를 갱신할 수 없음
     */
    public void markFailed(
            String ownerInstanceId,
            int expectedAttemptCount,
            Instant now,
            String errorCode,
            String errorDetail
    ) {
        requireActiveAttempt(ownerInstanceId, expectedAttemptCount, now);

        // 재선점 가능하도록 실패한 작업을 갱신함
        status = "FAILED";
        completedAt = now;
        this.errorCode = errorCode;
        this.errorDetail = errorDetail;
        updatedAt = now;
    }

    /**
     * 실행 시작 및 재시작의 상태를 초기화
     * 시도 번호는 호출 측에서 설정
     */
    private void startAttempt(
            String ownerInstanceId,
            Instant now,
            Duration leaseDuration
    ) {
        status = "RUNNING";
        this.ownerInstanceId = ownerInstanceId;
        startedAt = now;
        heartbeatAt = now;
        leaseExpiresAt = now.plus(leaseDuration);

        // 이전 시도 결과 필드 초기화
        completedAt = null;
        resultSummary = null;
        errorCode = null;
        errorDetail = null;
        updatedAt = now;
    }

    /**
     * 작업 갱신자가 현재 Lease를 보유하고 있는지 검증
     * 인스턴스ID와 시도 번호를 함께 비교, 유효 기간 체크
     */
    private void requireActiveAttempt(
            String ownerInstanceId,
            int expectedAttemptCount,
            Instant now
    ) {
        Objects.requireNonNull(now, "now");

        if (! "RUNNING".equals(status) ||
                ! Objects.equals(this.ownerInstanceId, ownerInstanceId) ||
                attemptCount != expectedAttemptCount ||
                ! now.isBefore(leaseExpiresAt)
        ) {
            throw new IllegalStateException("Scheduler 실행 Lease가 진행 중이 아닙니다.");
        }
    }

    /**
     * Lease 기간 유효 검증
     * 실행권은 반드시 양수 기간
     */
    private static void validateLeaseDuration(Duration duration) {
        Objects.requireNonNull(duration, "leaseDuration");

        if (duration.isZero() || duration.isNegative()) {
            throw new IllegalArgumentException("Lease 기간은 반드시 양수이어야 합니다.");
        }
    }
}
