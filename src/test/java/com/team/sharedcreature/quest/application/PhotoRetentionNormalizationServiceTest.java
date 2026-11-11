package com.team.sharedcreature.quest.application;

import com.team.sharedcreature.admin.application.SchedulerClaimTicket;
import com.team.sharedcreature.admin.application.SchedulerExecutionLifecycleService;
import com.team.sharedcreature.admin.application.SchedulerJobRunner;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * 4-H-2 사진 정리 실행권의 논리 시각과 Heartbeat 연결을 검증합니다.
 * 실제 SchedulerJobRunner는 별도의 MySQL 통합 테스트에서 검증합니다.
 * 업무 콜백의 실행을 Mock으로 재현해 결과 기록 경계를 확인합니다.
 */
class PhotoRetentionNormalizationServiceTest {

    private static final Instant NOW = Instant.parse("2026-11-10T09:07:45Z");
    private static final Instant BUCKET = Instant.parse("2026-11-10T09:05:00Z");
    private static final SchedulerClaimTicket TICKET = new SchedulerClaimTicket(
            77L, PhotoRetentionNormalizationService.JOB_TYPE, BUCKET, "test-owner", 1);

    private final SchedulerJobRunner runner = mock(SchedulerJobRunner.class);
    private final SchedulerExecutionLifecycleService lifecycle = mock(SchedulerExecutionLifecycleService.class);
    private final PhotoRetentionWork work = mock(PhotoRetentionWork.class);
    private final PhotoRetentionNormalizationService service = new PhotoRetentionNormalizationService(
            runner, lifecycle, work, Clock.fixed(NOW, ZoneOffset.UTC));

    /**
     * 현재 UTC 5분 구간 하나만 실행권으로 사용합니다.
     * 업무 요약 JSON은 작업 종료 후 Scheduler에 전달합니다.
     * 실제 업무가 끝날 때까지 반환된 요약을 보존합니다.
     */
    @Test
    void executesOneCurrentUtcBucket() {
        PhotoRetentionWork.Summary summary = new PhotoRetentionWork.Summary(2, 3, 4, 0, true);
        when(work.cleanup(eq(NOW), any(Runnable.class))).thenReturn(summary);
        AtomicReference<String> json = new AtomicReference<>();
        when(runner.run(eq(PhotoRetentionNormalizationService.JOB_TYPE), eq(BUCKET), anyString(),
                eq(Duration.ofMinutes(3)), ArgumentMatchers.<Function<SchedulerClaimTicket, String>>any()))
                .thenAnswer(invocation -> {
                    Function<SchedulerClaimTicket, String> callback = invocation.getArgument(4);
                    json.set(callback.apply(TICKET));
                    return SchedulerJobRunner.ExecutionResult.SUCCEEDED;
                });

        var result = service.normalize();
        assertEquals(BUCKET, result.scheduledFor());
        assertEquals(SchedulerJobRunner.ExecutionResult.SUCCEEDED, result.execution());
        assertSame(summary, result.summary());
        assertTrue(json.get().contains("\"validFilesDeleted\":4"));
        assertNull(result.error());
        verify(work).cleanup(eq(NOW), any(Runnable.class));
    }

    /**
     * 다른 서버 RUNNING 또는 이미 SUCCEEDED인 실행권은 건너뜁니다.
     * SKIPPED일 때에는 정리 업무를 수행하지 않습니다.
     * Heartbeat도 호출해서는 안 됩니다.
     */
    @Test
    void skippedClaimNeverRunsCleanup() {
        when(runner.run(anyString(), any(Instant.class), anyString(), any(Duration.class),
                ArgumentMatchers.<Function<SchedulerClaimTicket, String>>any()))
                .thenReturn(SchedulerJobRunner.ExecutionResult.SKIPPED);

        var result = service.normalize();
        assertEquals(SchedulerJobRunner.ExecutionResult.SKIPPED, result.execution());
        assertNull(result.summary());
        assertNull(result.error());
        verifyNoInteractions(work, lifecycle);
    }

    /**
     * 어떤 파일이 정리 실패했다면 성공 이력으로 기록하면 안 됩니다.
     * 실제 Runner는 업무 예외를 받아 FAILED를 Commit합니다.
     * 이 서비스는 부분 성공 요약과 오류를 보존합니다.
     */
    @Test
    void failedItemsCannotMarkJobSucceeded() {
        PhotoRetentionWork.Summary summary = new PhotoRetentionWork.Summary(1, 0, 0, 1, false);
        when(work.cleanup(eq(NOW), any(Runnable.class))).thenReturn(summary);
        mockRunnerExecutesJob();

        var result = service.normalize();
        assertNull(result.execution());
        assertSame(summary, result.summary());
        assertTrue(result.error().contains("사진 정리 항목 실패"));
    }

    /**
     * 실행권을 부여받은 업무에 동일한 티켓의 갱신 함수를 전달합니다.
     * 갱신은 SchedulerExecutionLifecycleService의 REQUIRES_NEW를 호출합니다.
     * 정상 작업 종료 전에 Lease 갱신의 성공이 확인되어야 합니다.
     */
    @Test
    void heartbeatRenewsExactClaimTicket() {
        when(work.cleanup(eq(NOW), any(Runnable.class))).thenAnswer(invocation -> {
            Runnable heartbeat = invocation.getArgument(1);
            heartbeat.run();
            return new PhotoRetentionWork.Summary(0, 1, 0, 0, false);
        });
        mockRunnerExecutesJob();

        var result = service.normalize();
        assertEquals(SchedulerJobRunner.ExecutionResult.SUCCEEDED, result.execution());
        verify(lifecycle).renewLease(TICKET, Duration.ofMinutes(3));
    }

    /**
     * Lease 갱신 중 소유권을 잃으면 정리 업무를 계속하지 않습니다.
     * 콜백의 RuntimeException은 실제 Runner가 FAILED 처리 시도합니다.
     * 경계 서비스의 결과에는 성공 이력을 만들지 않습니다.
     */
    @Test
    void lostLeaseDoesNotReportSuccess() {
        when(work.cleanup(eq(NOW), any(Runnable.class))).thenAnswer(invocation -> {
            Runnable heartbeat = invocation.getArgument(1);
            heartbeat.run();
            return new PhotoRetentionWork.Summary(0, 0, 0, 0, false);
        });
        doThrow(new IllegalStateException("lease lost"))
                .when(lifecycle).renewLease(TICKET, Duration.ofMinutes(3));
        mockRunnerExecutesJob();

        var result = service.normalize();
        assertNull(result.execution());
        assertTrue(result.error().contains("lease lost"));
    }

    /**
     * 외부 트랜잭션 안에서는 파일 정리 작업권을 선점할 수 없습니다.
     * 테스트의 스레드 플래그는 종료 시 원래 상태로 복원합니다.
     * 실행권 및 정리 서비스가 호출되지 않았음을 확인합니다.
     */
    @Test
    void rejectsExistingTransaction() {
        boolean previous = TransactionSynchronizationManager.isActualTransactionActive();
        try {
            TransactionSynchronizationManager.setActualTransactionActive(true);
            assertThrows(IllegalStateException.class, service::normalize);
            verifyNoInteractions(runner, work, lifecycle);
        } finally {
            TransactionSynchronizationManager.setActualTransactionActive(previous);
        }
    }

    /**
     * 음수 건수는 요약 객체가 생성되는 시점에서 거절합니다.
     * 잘못된 카운트는 Scheduler JSON 결과에 저장하면 안 됩니다.
     * 정상적인 빈 작업은 모든 값이 0일 수 있습니다.
     */
    @Test
    void rejectsInvalidSummary() {
        assertThrows(IllegalArgumentException.class,
                () -> new PhotoRetentionWork.Summary(-1, 0, 0, 0, false));
    }

    /**
     * Mock Runner가 실행권을 보유한 콜백을 실제로 호출하도록 구성합니다.
     * Mock이 콜백을 호출하지 않으면 업무 경계 검증이 이루어지지 않습니다.
     * 콜백 예외는 그대로 전파하여 실제 Runner의 실패 상황을 재현합니다.
     */
    private void mockRunnerExecutesJob() {
        when(runner.run(anyString(), any(Instant.class), anyString(), any(Duration.class),
                ArgumentMatchers.<Function<SchedulerClaimTicket, String>>any()))
                .thenAnswer(invocation -> {
                    Function<SchedulerClaimTicket, String> callback = invocation.getArgument(4);
                    callback.apply(TICKET);
                    return SchedulerJobRunner.ExecutionResult.SUCCEEDED;
                });
    }
}
