package com.team.sharedcreature.admin.application;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Scheduler 실행권 선점, 실제 업무 실행 및 결과 기록을 조정합니다.
 * 기존 Supplier 작업과 실행권이 필요한 Function 작업을 모두 지원합니다.
 * 업무 트랜잭션을 직접 소유하지 않고 각 Service의 Commit 이후 성공을 확정합니다.
 */
@Slf4j
@Service
public class SchedulerJobRunner {

    private final SchedulerExecutionService claimService;
    private final SchedulerExecutionLifecycleService lifecycleService;

    /**
     * 실행권 선점과 성공·실패 기록을 담당할 Service를 주입받습니다.
     * 실제 업무 작업은 run 메서드의 콜백에서 호출합니다.
     * 긴 작업의 Heartbeat는 실행권을 전달받은 콜백이 수행합니다.
     */
    public SchedulerJobRunner(
            SchedulerExecutionService claimService,
            SchedulerExecutionLifecycleService lifecycleService
    ) {
        this.claimService = claimService;
        this.lifecycleService = lifecycleService;
    }

    /**
     * Scheduler 공통 실행 결과를 표현합니다.
     * SKIPPED는 다른 실행자가 처리 중이거나 이미 완료됐다는 의미입니다.
     * SUCCEEDED는 업무와 Scheduler 이력의 성공 기록이 모두 완료된 상태입니다.
     */
    public enum ExecutionResult {
        SKIPPED,
        SUCCEEDED
    }

    /**
     * 기존 Supplier 기반 업무 호출과의 호환성을 유지합니다.
     * 별도 실행권이 필요 없는 단순 작업은 기존 코드를 그대로 호출할 수 있습니다.
     * 작업 실행과 결과 기록은 아래의 실행권 전달 오버로드에 위임합니다.
     *
     * @param jobType 논리 작업 종류
     * @param scheduledFor 해당 작업의 논리 실행 시각
     * @param ownerInstanceId 실행 서버 식별자
     * @param leaseDuration 실행권 유효 기간
     * @param job 실행권을 직접 사용하지 않는 업무
     * @return 실행 결과
     */
    public ExecutionResult run(
            String jobType,
            Instant scheduledFor,
            String ownerInstanceId,
            Duration leaseDuration,
            Supplier<String> job
    ) {
        Objects.requireNonNull(job, "job");

        // 기존 콜백을 실행권 전달형 콜백으로 감싸 기능을 재사용합니다.
        return run(
                jobType,
                scheduledFor,
                ownerInstanceId,
                leaseDuration,
                ticket -> job.get()
        );
    }

    /**
     * 실행권을 업무 콜백에 전달하는 Scheduler 실행 진입점입니다.
     * 트랜잭션 외부에서 선점하고 콜백의 각 독립 Commit이 끝난 뒤 성공 기록합니다.
     * 업무 예외를 기록하되 성공 기록 실패를 업무 실패로 재분류하지 않습니다.
     *
     * @param jobType 논리 작업 종류
     * @param scheduledFor 해당 작업이 처리됐어야 하는 논리 시각
     * @param ownerInstanceId 실행 인스턴스 식별자
     * @param leaseDuration Lease 유효 기간
     * @param job 선점한 티켓을 받아 업무를 수행하는 콜백
     * @return 실행 결과
     */
    public ExecutionResult run(
            String jobType,
            Instant scheduledFor,
            String ownerInstanceId,
            Duration leaseDuration,
            Function<SchedulerClaimTicket, String> job
    ) {
        Objects.requireNonNull(job, "job");

        // 외부 업무 트랜잭션 안에서 SUCCEEDED만 먼저 확정되는 상황을 차단합니다.
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException(
                    "SchedulerJobRunner는 기존 트랜잭션 내부에서 호출될 수 없습니다."
            );
        }

        // 동일 jobType·scheduledFor는 DB UNIQUE 제약으로 하나만 선점합니다.
        Optional<SchedulerClaimTicket> claimed = claimService.tryClaim(
                jobType, scheduledFor, ownerInstanceId, leaseDuration
        );
        if (claimed.isEmpty()) {
            return ExecutionResult.SKIPPED;
        }

        SchedulerClaimTicket ticket = claimed.orElseThrow();
        String summary;
        try {
            // 업무 콜백은 별도 Service의 트랜잭션이 모두 종료된 후 반환해야 합니다.
            summary = Objects.requireNonNull(
                    job.apply(ticket), "업무 결과 JSON은 null이 아니어야 합니다."
            );
        } catch (RuntimeException | Error error) {
            // 실행권이 만료됐으면 기록 실패를 suppressed로 보존합니다.
            recordFailure(ticket, error);
            throw error;
        }

        // 이미 Commit된 업무를 성공 기록 실패 때문에 FAILED로 덮어쓰지 않습니다.
        lifecycleService.markSucceeded(ticket, summary);
        return ExecutionResult.SUCCEEDED;
    }

    /**
     * 실제 업무에서 발생한 원래 예외를 유지하면서 FAILED를 기록합니다.
     * Lease 상실이나 DB 오류로 실패 기록도 거절될 수 있습니다.
     * 그때는 부가 예외를 suppressed로 남겨 최초 원인을 가리지 않습니다.
     *
     * @param ticket 선점한 실행권
     * @param error 업무 실행에서 발생한 오류
     */
    private void recordFailure(SchedulerClaimTicket ticket, Throwable error) {
        String detail = "%s: %s".formatted(
                error.getClass().getSimpleName(), error.getMessage()
        );

        // DB error_detail 컬럼 길이를 넘기지 않습니다.
        if (detail.length() > 1000) {
            detail = detail.substring(0, 1000);
        }

        try {
            lifecycleService.markFailed(ticket, "JOB_EXECUTION_FAILED", detail);
        } catch (RuntimeException recordError) {
            error.addSuppressed(recordError);
            log.warn("Scheduler 실패 이력 기록 오류. executionId={}",
                    ticket.executionId(), recordError);
        }
    }
}
