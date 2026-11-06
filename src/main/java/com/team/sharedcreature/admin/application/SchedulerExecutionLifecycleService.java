package com.team.sharedcreature.admin.application;

import com.team.sharedcreature.admin.domain.SchedulerExecution;
import com.team.sharedcreature.admin.infrastructure.SchedulerExecutionRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

/**
 * Scheduler 실행권의 갱신과 종료 상태를 관리한다.
 * 실행 이력 행을 배타 잠금하고, 현재 실행자의 소유권을 검증한다.
 * 각 상태 변경은 독립적인 DB 트랜잭션에서 Commit 됨.
 */
@Slf4j
@Service
public class SchedulerExecutionLifecycleService {

    private final SchedulerExecutionRepository repository;
    private final Clock clock;

    public SchedulerExecutionLifecycleService(SchedulerExecutionRepository repository, Clock clock) {
        this.repository = repository;
        this.clock = clock;
    }

    /**
     * 현재 실행자의 Heartbeat를 기록하고 Lease를 연장함.
     * 유효한 RUNNING 실행만 가능.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void renewLease(SchedulerClaimTicket ticket, Duration leaseDuration) {
        Objects.requireNonNull(leaseDuration, "leaseDuration");

        // 실행 이력 행을 잠근 뒤 엔티티 규칙 적용
        SchedulerExecution execution = lockExecution(ticket);
        Instant now = Instant.now(clock);

        execution.renewLease(
                ticket.ownerInstanceId(),
                ticket.attemptCount(),
                now,
                leaseDuration
        );
    }

    /**
     * 현재 실행의 성공 결과를 기록함.
     *  반드시 업무 처리가 Commit된 후에 호출할 것.
     *  만료되거나 재선점된 실행은 처리되지 않음.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markSucceeded(SchedulerClaimTicket ticket, String resultSummary) {
        Objects.requireNonNull(resultSummary, "resultSummary");

        // 실행권 검증을 통과해야 성공 상태로 전환된다.
        SchedulerExecution execution = lockExecution(ticket);

        execution.markSucceeded(
                ticket.ownerInstanceId(),
                ticket.attemptCount(),
                Instant.now(clock),
                resultSummary
        );
    }

    /**
     * 현재 실행의 실패 원인과 상태를 기록한다.
     * 다음 재시도에서 FAILED 상태를 재선점할 수 있다.
     * Lease를 상실한 진행자는 실패 기록도 덮어쓸 수 없다.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markFailed(
            SchedulerClaimTicket ticket,
            String errorCode,
            String errorDetail
    ) {
        if (errorCode == null || errorCode.isBlank() || errorCode.length() > 100) {
            throw new IllegalArgumentException("잘못된 errorCode입니다.");
        }

        if (errorDetail != null && errorDetail.length() > 1000) {
            throw new IllegalArgumentException("errorDetail이 너무 깁니다.");
        }

        SchedulerExecution execution = lockExecution(ticket);

        execution.markFailed(
                ticket.ownerInstanceId(),
                ticket.attemptCount(),
                Instant.now(clock),
                errorCode,
                errorDetail
        );
    }

    /**
     * 실행권 ID와 논리 작업 키에 해당하는 DB 행을 배타 잠금한다.
     */
    private SchedulerExecution lockExecution(SchedulerClaimTicket ticket) {
        Objects.requireNonNull(ticket, "ticket");

        SchedulerExecution execution = repository.findForUpdateById(ticket.executionId())
                .orElseThrow(() -> new IllegalStateException("Scheduler 실행을 찾지 못했습니다."));

        // 다른 논리 작업의 실행권인지 검증
        if (! Objects.equals(execution.getJobType(), ticket.jobType()) ||
            ! Objects.equals(execution.getScheduledFor(), ticket.scheduledFor())
        ) {
            throw new IllegalStateException("Scheduler 실행 티켓이 일치하지 않습니다.");
        }

        return execution;
    }

}
