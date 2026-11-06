
package com.team.sharedcreature.admin.application;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Scheduler 공통 실행기의 호출 순서를 검증합니다.
 * 실행권을 획득한 경우에만 실제 업무가 수행되어야 합니다.
 * 업무 실패와 결과 기록 실패를 서로 구분합니다.
 */
class SchedulerJobRunnerTest {

    private static final String JOB_TYPE = "QUEST_DAILY";
    private static final String OWNER = "server-a";

    private static final Instant SCHEDULED_FOR =
            Instant.parse("2026-11-06T00:00:00Z");

    private static final Duration LEASE =
            Duration.ofMinutes(5);

    private static final SchedulerClaimTicket TICKET =
            new SchedulerClaimTicket(
                    1L, JOB_TYPE, SCHEDULED_FOR, OWNER, 1
            );

    private final SchedulerExecutionService claimService =
            mock(SchedulerExecutionService.class);

    private final SchedulerExecutionLifecycleService lifecycleService =
            mock(SchedulerExecutionLifecycleService.class);

    private final SchedulerJobRunner runner =
            new SchedulerJobRunner(claimService, lifecycleService);

    /**
     * 실행권 선점에 실패하면 업무를 실행하지 않습니다.
     * 다른 Scheduler가 이미 실행 중일 수 있습니다.
     * 성공 또는 실패 이력도 변경하면 안 됩니다.
     */
    @Test
    void skippedJobDoesNotExecuteBusiness() {
        when(claimService.tryClaim(
                JOB_TYPE, SCHEDULED_FOR, OWNER, LEASE
        )).thenReturn(Optional.empty());

        AtomicBoolean executed = new AtomicBoolean(false);

        // 실행권이 없으므로 작업 함수는 호출되지 않습니다.
        var result = run(() -> {
            executed.set(true);
            return "{}";
        });

        assertEquals(
                SchedulerJobRunner.ExecutionResult.SKIPPED,
                result
        );
        assertFalse(executed.get());

        verifyNoInteractions(lifecycleService);
    }

    /**
     * 실제 업무가 끝난 뒤 성공 기록을 수행합니다.
     * 실행권을 보유한 경우에만 작업을 호출합니다.
     * 성공 기록 시점에는 작업 함수가 이미 반환됐어야 합니다.
     */
    @Test
    void successfulJobRecordsSuccessAfterBusiness() {
        allowClaim();

        AtomicBoolean businessFinished = new AtomicBoolean(false);

        doAnswer(invocation -> {
            // 성공 기록은 업무 종료 이후에만 호출돼야 합니다.
            assertTrue(businessFinished.get());
            return null;
        }).when(lifecycleService).markSucceeded(
                eq(TICKET),
                eq("{\"processed\":1}")
        );

        var result = run(() -> {
            businessFinished.set(true);
            return "{\"processed\":1}";
        });

        assertEquals(
                SchedulerJobRunner.ExecutionResult.SUCCEEDED,
                result
        );

        verify(lifecycleService).markSucceeded(
                TICKET, "{\"processed\":1}"
        );
        verify(lifecycleService, never()).markFailed(
                any(), any(), any()
        );
    }

    /**
     * 업무 실행 중 예외가 발생하면 실패를 기록합니다.
     * 성공 기록을 남기면 안 됩니다.
     * 호출자에게는 최초 업무 예외가 그대로 전달되어야 합니다.
     */
    @Test
    void businessFailureRecordsFailedStatus() {
        allowClaim();

        IllegalStateException original =
                new IllegalStateException("Business failed");

        // 실제 업무에서 발생한 오류를 그대로 전파합니다.
        IllegalStateException thrown = assertThrows(
                IllegalStateException.class,
                () -> run(() -> {
                    throw original;
                })
        );

        assertSame(original, thrown);

        verify(lifecycleService).markFailed(
                eq(TICKET),
                eq("JOB_EXECUTION_FAILED"),
                contains("Business failed")
        );

        verify(lifecycleService, never()).markSucceeded(
                any(), any()
        );
    }

    /**
     * 실패 기록 자체가 실패해도 원래 업무 오류를 보존합니다.
     * 기록 실패 예외는 suppressed에 추가합니다.
     * Lease 상실 등으로 실패 기록이 거절되는 경우를 검증합니다.
     */
    @Test
    void failureRecordingDoesNotHideOriginalError() {
        allowClaim();

        IllegalStateException original =
                new IllegalStateException("Business failed");

        doThrow(new IllegalStateException("Lease expired"))
                .when(lifecycleService)
                .markFailed(any(), any(), any());

        // 실패 기록의 오류가 최초 업무 오류를 대체하지 않습니다.
        IllegalStateException thrown = assertThrows(
                IllegalStateException.class,
                () -> run(() -> {
                    throw original;
                })
        );

        assertSame(original, thrown);
        assertEquals(1, thrown.getSuppressed().length);
        assertEquals(
                "Lease expired",
                thrown.getSuppressed()[0].getMessage()
        );
    }

    /**
     * 성공 기록 실패 후 FAILED 기록을 강제로 수행하지 않습니다.
     * 업무는 이미 Commit됐을 가능성이 있습니다.
     * 성공 확정 실패는 호출자에게 그대로 전달되어야 합니다.
     */
    @Test
    void successRecordingFailureIsPropagated() {
        allowClaim();

        IllegalStateException failure =
                new IllegalStateException("Lease expired");

        doThrow(failure).when(lifecycleService)
                .markSucceeded(eq(TICKET), eq("{}"));

        // 업무 완료 후 성공 기록 단계에서 오류가 발생합니다.
        IllegalStateException thrown = assertThrows(
                IllegalStateException.class,
                () -> run(() -> "{}")
        );

        assertSame(failure, thrown);

        verify(lifecycleService, never()).markFailed(
                any(), any(), any()
        );
    }

    /**
     * 공통 실행기를 동일한 입력으로 호출합니다.
     * 개별 테스트에서는 업무 함수만 바꿉니다.
     * 중복된 테스트 호출 코드를 줄입니다.
     */
    private SchedulerJobRunner.ExecutionResult run(
            java.util.function.Supplier<String> job
    ) {
        return runner.run(
                JOB_TYPE,
                SCHEDULED_FOR,
                OWNER,
                LEASE,
                job
        );
    }

    /**
     * 실행권 획득에 성공한 상황을 준비합니다.
     * 실제 DB 접근은 하지 않습니다.
     * 후속 실행 순서만 독립적으로 검증합니다.
     */
    private void allowClaim() {
        when(claimService.tryClaim(
                JOB_TYPE, SCHEDULED_FOR, OWNER, LEASE
        )).thenReturn(Optional.of(TICKET));
    }
}
