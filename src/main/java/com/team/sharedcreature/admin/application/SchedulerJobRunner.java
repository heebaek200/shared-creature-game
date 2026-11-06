package com.team.sharedcreature.admin.application;

import lombok.extern.slf4j.Slf4j;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * Scheduler 작업의 선점, 실행, 결과 기록을 조정한다.
 * 업무 처리 Service의 트랜잭션이 정상 종료된 뒤 성공을 기록한다.
 * 실제 업무는 호출자가 전달한 콜백 작업 함수를 통해 수행한다.
 */
@Slf4j
@Service
public class SchedulerJobRunner {

    private final SchedulerExecutionService claimService;
    private final SchedulerExecutionLifecycleService lifecycleService;

    public SchedulerJobRunner(SchedulerExecutionService claimService, SchedulerExecutionLifecycleService lifecycleService) {
        this.claimService = claimService;
        this.lifecycleService = lifecycleService;
    }

    /**
     * Scheduler 공통 실행기의 처리 결과.
     * SKIPPED  : 다른 실행자가 처리 중이거나 이미 완료한 경우
     * SUCCEEDED: 업무 실행과 성공 기록이 모두 완료된 경우
     */
    public enum ExecutionResult {
        SKIPPED, SUCCEEDED
    }

    /**
     * 하나의 논리 Scheduler 작업을 실행한다.
     * 선점 실패 시 작업을 건너뛰고 SKIPPED를 반환한다.
     * 업무 예외는 실패 기록을 시도한 뒤 원래 예외를 전파한다.
     */
    public ExecutionResult run(
            String jobType,
            Instant scheduledFor,
            String ownerInstanceId,
            Duration leaseDuration,
            Supplier<String> job
    ) {
        Objects.requireNonNull(job, "job");

        // 업무 시작 전 DB 실행권 획득
        Optional<SchedulerClaimTicket> claimTicketOptionalmed = claimService.tryClaim(
                jobType, scheduledFor, ownerInstanceId, leaseDuration
        );

        if (claimTicketOptionalmed.isEmpty()) {
            return ExecutionResult.SKIPPED;
        }

        SchedulerClaimTicket ticket = claimTicketOptionalmed.orElseThrow();
        String resultSummary;

        try {
            resultSummary = Objects.requireNonNull(job.get(), "업무 결과 JSON은 null이 아니어야 합니다.");
            // 예상 예외 상황: 업무 Service가 반환되었는데 해당 트랜잭션이 종료되지 않음
        } catch (RuntimeException | Error e) {
            // 업무 실패 기록. 예외는 밖에서 처리.
            recordFailure(ticket, e);
            throw e;
        }

        // 업무 Commit 이후 별도 트랜잭션에서 성공을 확정함
        lifecycleService.markSucceeded(ticket, resultSummary);

        return ExecutionResult.SUCCEEDED;
    }

    /**
     * 업무 실패를 Scheduler 실행 이력에 기록함.
     *  - 기록 도중 발생할 수 있는 또 다른 예외는 보존하고 원래 업무 오류 유지
     */
    private void recordFailure(SchedulerClaimTicket ticket, Throwable error) {
        String detail = "%s: %s".formatted(error.getClass().getSimpleName(), error.getMessage());

        // error_detail 컬럼의 최대 길이 자름
        if (detail.length() > 1000) {
            detail = detail.substring(0, 1000);
        }

        try {
            lifecycleService.markFailed(
                    ticket,
                    "JOB_EXECUTION_FAILED",
                    detail
            );
        } catch (RuntimeException recordError) {
            error.addSuppressed(recordError);

            log.warn(
                    "Scheduler 예외처리 중 이력 작성에 실패했습니다. executionId={}",
                    ticket.executionId(),
                    recordError
            );
        }
    }
}
