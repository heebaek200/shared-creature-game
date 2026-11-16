package com.team.sharedcreature.admin.application;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * 공통 Scheduler 실행기의 새 티켓 전달형 오버로드를 검증합니다.
 * 기존 Supplier 기반 run() API가 변경되지 않는지도 함께 확인합니다.
 * 실제 실행 이력 DB 대신 Mockito로 선점 및 상태 기록만 대체합니다.
 */
class SchedulerJobRunnerTicketTest {

    private static final Instant SCHEDULED_FOR = Instant.parse("2026-11-29T15:00:00Z");
    private static final Duration LEASE = Duration.ofMinutes(10);
    private static final SchedulerClaimTicket TICKET = new SchedulerClaimTicket(
            55L, "SEASON_CLOSE", SCHEDULED_FOR, "server-a", 1
    );

    private final SchedulerExecutionService claimService = mock(SchedulerExecutionService.class);
    private final SchedulerExecutionLifecycleService lifecycleService =
            mock(SchedulerExecutionLifecycleService.class);
    private final SchedulerJobRunner runner = new SchedulerJobRunner(claimService, lifecycleService);

    /**
     * 티켓 전달형 콜백이 선점 완료 이후 정확한 티켓을 받는지 확인합니다.
     * 호출자는 이를 이용해 배치 사이에 Heartbeat를 수행할 수 있습니다.
     * 업무가 반환한 JSON은 그대로 SUCCEEDED 기록에 전달되어야 합니다.
     */
    @Test
    void claimTicketIsPassedToBusinessCallback() {
        when(claimService.tryClaim("SEASON_CLOSE", SCHEDULED_FOR, "server-a", LEASE))
                .thenReturn(Optional.of(TICKET));
        AtomicReference<SchedulerClaimTicket> seen = new AtomicReference<>();

        // 인자가 있는 lambda는 Function 오버로드를 선택합니다.
        var result = runner.run("SEASON_CLOSE", SCHEDULED_FOR, "server-a", LEASE,
                ticket -> {
                    seen.set(ticket);
                    return "{\"ok\":true}";
                });

        assertEquals(SchedulerJobRunner.ExecutionResult.SUCCEEDED, result);
        assertSame(TICKET, seen.get());
        verify(lifecycleService).markSucceeded(TICKET, "{\"ok\":true}");
    }

    /**
     * 기존 인자 없는 Supplier 업무 호출과의 호환성을 확인합니다.
     * 기존 SchedulerJobRunnerTest와 기존 코드 경로를 유지합니다.
     * 성공 기록은 업무 반환값을 그대로 사용해야 합니다.
     */
    @Test
    void oldSupplierOverloadRemainsCompatible() {
        when(claimService.tryClaim("SEASON_CLOSE", SCHEDULED_FOR, "server-a", LEASE))
                .thenReturn(Optional.of(TICKET));

        // 인자가 없는 lambda는 Supplier 오버로드를 선택합니다.
        var result = runner.run("SEASON_CLOSE", SCHEDULED_FOR, "server-a", LEASE,
                () -> "{}");

        assertEquals(SchedulerJobRunner.ExecutionResult.SUCCEEDED, result);
        verify(lifecycleService).markSucceeded(TICKET, "{}");
    }

    /**
     * 선점에 실패하면 티켓 전달형 콜백을 실행하지 않습니다.
     * 다른 인스턴스가 처리 중인 작업의 중복 실행을 방지합니다.
     * 성공·실패 상태 기록도 변경하지 않아야 합니다.
     */
    @Test
    void noTicketDoesNotInvokeCallback() {
        when(claimService.tryClaim("SEASON_CLOSE", SCHEDULED_FOR, "server-a", LEASE))
                .thenReturn(Optional.empty());

        // 콜백을 호출하면 즉시 테스트에 실패하도록 구성합니다.
        var result = runner.run("SEASON_CLOSE", SCHEDULED_FOR, "server-a", LEASE,
                ticket -> {
                    fail("선점 실패 후 업무 실행됨");
                    return "{}";
                });

        assertEquals(SchedulerJobRunner.ExecutionResult.SKIPPED, result);
        verifyNoInteractions(lifecycleService);
    }
}
