package com.team.sharedcreature.payment.application;

import com.team.sharedcreature.admin.application.SchedulerClaimTicket;
import com.team.sharedcreature.admin.application.SchedulerExecutionLifecycleService;
import com.team.sharedcreature.admin.application.SchedulerJobRunner;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * #24의 UTC 5분 실행권, 집계 이력 및 부분 실패 방어를 단위 테스트합니다.
 * 업무 Service와 Runner는 Mock으로 두되 실제 티켓 전달형 콜백은 실행합니다.
 * 외부 PortOne 연동과 MySQL 행 잠금은 별도 #21/#24 통합 검증 범위입니다.
 */
class PaymentWebhookReconciliationNormalizationServiceTest {
    private static final Instant NOW = Instant.parse("2026-11-11T01:07:45Z");
    private static final Instant BUCKET = Instant.parse("2026-11-11T01:05:00Z");
    private final SchedulerJobRunner runner = mock(SchedulerJobRunner.class);
    private final PaymentWebhookRecoveryService recovery = mock(PaymentWebhookRecoveryService.class);
    private final PaymentWebhookLeaseHeartbeat heartbeat = new PaymentWebhookLeaseHeartbeat(
            mock(SchedulerExecutionLifecycleService.class));
    private final PaymentWebhookReconciliationNormalizationService normalizer =
            new PaymentWebhookReconciliationNormalizationService(
                    runner, recovery, heartbeat, Clock.fixed(NOW, ZoneOffset.UTC));

    /**
     * UTC 논리 시각과 #21 집계 결과의 JSON 문자열을 확인합니다.
     * 실제 실행권 콜백은 Mock Runner에서 직접 호출합니다.
     * 성공 결과는 SUCCEEDED로 기록되어야 합니다.
     */
    @Test
    void succeedsWithCurrentUtcBucketAndExactSummary() {
        var summary = new PaymentWebhookRecoveryService.PaymentWebhookRecoveryResult(3, 2, 1, 0);
        when(recovery.recoverStaleWebhooks()).thenReturn(summary);
        AtomicReference<String> json = new AtomicReference<>();
        when(runner.run(eq(PaymentWebhookReconciliationNormalizationService.JOB_TYPE),
                eq(BUCKET), anyString(), eq(Duration.ofMinutes(3)),
                org.mockito.ArgumentMatchers.<Function<SchedulerClaimTicket, String>>any()))
                .thenAnswer(invocation -> {
                    Function<SchedulerClaimTicket, String> callback = invocation.getArgument(4);
                    json.set(callback.apply(ticket()));
                    return SchedulerJobRunner.ExecutionResult.SUCCEEDED;
                });

        var result = normalizer.normalize();
        assertEquals(BUCKET, result.scheduledFor());
        assertEquals(SchedulerJobRunner.ExecutionResult.SUCCEEDED, result.execution());
        assertSame(summary, result.summary());
        assertNull(result.error());
        assertFalse(result.alreadyRunning());
        assertTrue(json.get().contains("\"selectedCount\":3"));
        assertTrue(json.get().contains("\"processedCount\":2"));
        verify(recovery).recoverStaleWebhooks();
    }

    /**
     * 다른 서버가 처리 중이거나 이미 성공한 동일 구간은 건너뜁니다.
     * 복구 Service를 호출하지 않음으로써 불필요한 외부 PG 조회를 차단합니다.
     * SKIPPED는 성공 건수를 만들지 않아야 합니다.
     */
    @Test
    void skippedClaimNeverCallsRecovery() {
        when(runner.run(anyString(), any(Instant.class), anyString(), any(Duration.class),
                org.mockito.ArgumentMatchers.<Function<SchedulerClaimTicket, String>>any()))
                .thenReturn(SchedulerJobRunner.ExecutionResult.SKIPPED);

        var result = normalizer.normalize();
        assertEquals(SchedulerJobRunner.ExecutionResult.SKIPPED, result.execution());
        assertNull(result.summary());
        assertNull(result.error());
        verifyNoInteractions(recovery);
    }

    /**
     * 단건 실패가 있으면 이미 처리된 성공 건수가 있어도 전체 SUCCEEDED로 기록하지 않습니다.
     * Runner가 업무 예외를 받아 실패 이력을 기록할 수 있도록 콜백에서 던집니다.
     * 복구 집계와 오류 메시지는 호출 결과에도 보존합니다.
     */
    @Test
    void failedEventForcesFailedSchedulerRun() {
        var summary = new PaymentWebhookRecoveryService.PaymentWebhookRecoveryResult(4, 2, 1, 1);
        when(recovery.recoverStaleWebhooks()).thenReturn(summary);
        mockRunnerExecutesCallback();

        var result = normalizer.normalize();
        assertNull(result.execution());
        assertSame(summary, result.summary());
        assertTrue(result.error().contains("실패 건수: 1"));
    }

    /**
     * 원천 복구 Service가 DB 장애로 예외를 발생시키면 실패 결과로 격리합니다.
     * 이 경우 집계 결과를 만들어 기록하지 않습니다.
     * 실제 Runner는 동일 예외를 받은 뒤 실패 이력을 기록합니다.
     */
    @Test
    void infrastructureErrorDoesNotBecomeSuccess() {
        when(recovery.recoverStaleWebhooks()).thenThrow(new IllegalStateException("DB offline"));
        mockRunnerExecutesCallback();

        var result = normalizer.normalize();
        assertNull(result.execution());
        assertNull(result.summary());
        assertTrue(result.error().contains("DB offline"));
    }

    /**
     * #21 업무 복구 결과의 건수 합이 맞지 않으면 작업을 성공 처리하지 않습니다.
     * 유실되거나 중복 집계된 대상을 성공으로 숨기지 않기 위한 방어입니다.
     * 예외는 Runner를 거쳐 FAILED로 기록되어야 합니다.
     */
    @Test
    void mismatchedCountsCannotSucceed() {
        when(recovery.recoverStaleWebhooks()).thenReturn(
                new PaymentWebhookRecoveryService.PaymentWebhookRecoveryResult(3, 1, 0, 0));
        mockRunnerExecutesCallback();

        assertTrue(normalizer.normalize().error().contains("건수가 일치하지"));
    }

    /**
     * #21 계약상 최대 100건 초과가 반환되면 성공 처리하지 않습니다.
     * 잘못된 배치 크기는 시간 초과와 Lease 불일치 위험을 증가시킵니다.
     * 이후 정상 복구가 가능하도록 호출 결과에는 오류를 남깁니다.
     */
    @Test
    void rejectsOutOfRangeBatchCount() {
        when(recovery.recoverStaleWebhooks()).thenReturn(
                new PaymentWebhookRecoveryService.PaymentWebhookRecoveryResult(101, 101, 0, 0));
        mockRunnerExecutesCallback();

        assertNotNull(normalizer.normalize().error());
    }

    /**
     * 반환값이 null인 버그는 성공 이력으로 기록할 수 없습니다.
     * #21 서비스가 정상적으로 집계 객체를 반드시 반환한다는 계약을 확인합니다.
     * 이후 재시도가 가능한 실패 상태를 유지해야 합니다.
     */
    @Test
    void rejectsNullBusinessResult() {
        mockRunnerExecutesCallback();

        assertNotNull(normalizer.normalize().error());
    }

    /**
     * 외부 업무 트랜잭션 안에서 Scheduler 실행권을 선점하지 않습니다.
     * PG 호출 도중 DB 커넥션을 점유하는 구현을 방지하기 위한 검증입니다.
     * 변경한 스레드의 테스트 상태는 원래 값으로 복구합니다.
     */
    @Test
    void rejectsOuterTransaction() {
        boolean original = TransactionSynchronizationManager.isActualTransactionActive();
        try {
            TransactionSynchronizationManager.setActualTransactionActive(true);
            assertThrows(IllegalStateException.class, normalizer::normalize);
            verifyNoInteractions(runner, recovery);
        } finally {
            TransactionSynchronizationManager.setActualTransactionActive(original);
        }
    }

    /**
     * UTC 5분 경계 바로 앞에서는 이전 논리 구간을 사용합니다.
     * 경계 이후에는 다음 5분 구간이 생성되어야 합니다.
     * Host JVM의 로컬 시간대를 사용하지 않아야 합니다.
     */
    @Test
    void computesNextBoundaryWithoutTimezoneConversion() {
        var atBoundary = new PaymentWebhookReconciliationNormalizationService(
                runner,
                recovery,
                heartbeat, // 추가: 4-J-3에서 도입한 Heartbeat 의존성
                Clock.fixed(Instant.parse("2026-11-11T01:10:00Z"), ZoneOffset.UTC)
        );

        when(runner.run(
                anyString(),
                any(Instant.class),
                anyString(),
                any(Duration.class),
                org.mockito.ArgumentMatchers.<Function<SchedulerClaimTicket, String>>any()
        )).thenReturn(SchedulerJobRunner.ExecutionResult.SKIPPED);

        assertEquals(
                Instant.parse("2026-11-11T01:10:00Z"),
                atBoundary.normalize().scheduledFor()
        );
    }

    /**
     * 같은 JVM에서 첫 실행이 진행되는 동안 다음 호출은 건너뜁니다.
     * 스케줄 구간이 달라져도 로컬 중복 업무 수행을 하지 않습니다.
     * 첫 호출은 대기 해제 후 정상적으로 완료됩니다.
     */
    @Test
    void overlappingLocalInvocationsDoNotBothRun() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        when(recovery.recoverStaleWebhooks()).thenReturn(
                new PaymentWebhookRecoveryService.PaymentWebhookRecoveryResult(0, 0, 0, 0));
        when(runner.run(anyString(), any(Instant.class), anyString(), any(Duration.class),
                org.mockito.ArgumentMatchers.<Function<SchedulerClaimTicket, String>>any()))
                .thenAnswer(invocation -> {
                    entered.countDown();
                    if (!release.await(5, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("test barrier timeout");
                    }
                    Function<SchedulerClaimTicket, String> callback = invocation.getArgument(4);
                    callback.apply(ticket());
                    return SchedulerJobRunner.ExecutionResult.SUCCEEDED;
                });
        FutureTask<PaymentWebhookReconciliationNormalizationService.Result> task =
                new FutureTask<>(normalizer::normalize);
        Thread thread = new Thread(task, "payment-webhook-scheduler-test");
        thread.start();

        try {
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            var skipped = normalizer.normalize();
            assertTrue(skipped.alreadyRunning());
            assertEquals(SchedulerJobRunner.ExecutionResult.SKIPPED, skipped.execution());
            verifyNoInteractions(recovery);
        } finally {
            release.countDown();
        }
        assertEquals(SchedulerJobRunner.ExecutionResult.SUCCEEDED,
                task.get(5, TimeUnit.SECONDS).execution());
        thread.join(1_000);
        verify(recovery).recoverStaleWebhooks();
    }


    /**
     * PG 업무가 진행되는 동안 Heartbeat만 실패해도 성공으로 기록하지 않습니다.
     * 업무가 끝난 직후 Scope 종료에서 Lease 실패가 정상화 결과로 전파됩니다.
     * 실제 PG 조회를 사용하지 않고 짧은 갱신 간격만 테스트에 주입합니다.
     */
    @Test
    void asynchronousLeaseFailureMakesNormalizationFail() throws Exception {
        var lifecycle = mock(SchedulerExecutionLifecycleService.class);
        CountDownLatch tried = new CountDownLatch(1);
        doAnswer(invocation -> {
            tried.countDown();
            throw new IllegalStateException("lease lost");
        }).when(lifecycle).renewLease(any(SchedulerClaimTicket.class), eq(Duration.ofMinutes(3)));
        var fastHeartbeat = new PaymentWebhookLeaseHeartbeat(lifecycle, Duration.ofMillis(10));
        var service = new PaymentWebhookReconciliationNormalizationService(
                runner, recovery, fastHeartbeat, Clock.fixed(NOW, ZoneOffset.UTC));
        when(recovery.recoverStaleWebhooks()).thenAnswer(invocation -> {
            assertTrue(tried.await(2, TimeUnit.SECONDS));
            return new PaymentWebhookRecoveryService.PaymentWebhookRecoveryResult(0, 0, 0, 0);
        });
        mockRunnerExecutesCallback();

        var result = service.normalize();
        assertNull(result.execution());
        assertNotNull(result.error());
        assertTrue(result.error().contains("Lease 갱신에 실패"));
        verify(lifecycle, atLeastOnce()).renewLease(any(SchedulerClaimTicket.class), eq(Duration.ofMinutes(3)));
    }

    /**
     * Mock Runner에서 사용되는 실제 형태의 테스트 실행권을 생성합니다.
     * Callback이 전달받는 티켓 필드의 타입을 운영과 맞춥니다.
     * 별도 Heartbeat는 30초 뒤 시작되므로 빠른 단위 테스트에는 영향을 주지 않습니다.
     */
    private SchedulerClaimTicket ticket() {
        return new SchedulerClaimTicket(1L,
                PaymentWebhookReconciliationNormalizationService.JOB_TYPE, BUCKET, "owner-test", 1);
    }

    /**
     * Mock Runner에서 실제 실행권 전달형 업무 콜백을 실행합니다.
     * 이 콜백은 Heartbeat Scope를 시작하고 닫는 전체 처리 경로를 포함합니다.
     * 오류는 실제 Runner처럼 상위 서비스로 전달합니다.
     */
    private void mockRunnerExecutesCallback() {
        when(runner.run(anyString(), any(Instant.class), anyString(), any(Duration.class),
                org.mockito.ArgumentMatchers.<Function<SchedulerClaimTicket, String>>any()))
                .thenAnswer(invocation -> {
                    Function<SchedulerClaimTicket, String> callback = invocation.getArgument(4);
                    callback.apply(ticket());
                    return SchedulerJobRunner.ExecutionResult.SUCCEEDED;
                });
    }
}
