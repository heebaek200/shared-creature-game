package com.team.sharedcreature.payment.application;

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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * #22 구현체가 없는 상태에서 #24 환불 Scheduler 실행권 계약을 검증합니다.
 * 모든 환불 복구 업무는 Mock으로 대체하고, 실제 PG 취소를 요청하지 않습니다.
 * 확정 실패와 기술 오류의 차이 및 Heartbeat 전달 경계를 확인합니다.
 */
class RefundReconciliationNormalizationServiceTest {

    private static final Instant NOW = Instant.parse("2026-11-11T03:07:12Z");
    private static final Instant BUCKET = Instant.parse("2026-11-11T03:05:00Z");
    private static final Duration LEASE = Duration.ofMinutes(3);

    private final SchedulerJobRunner runner = mock(SchedulerJobRunner.class);
    private final SchedulerExecutionLifecycleService lifecycle = mock(SchedulerExecutionLifecycleService.class);
    private final RefundRecoveryWork work = mock(RefundRecoveryWork.class);
    private final RefundReconciliationNormalizationService service = new RefundReconciliationNormalizationService(
            runner, lifecycle, work, Clock.fixed(NOW, ZoneOffset.UTC));

    /**
     * 현재 UTC 5분 구간의 실행권을 사용하고 성공 숫자만 JSON에 기록합니다.
     * PortOne 최신 조회는 반드시 #22 연계 업무 서비스에서 수행해야 합니다.
     * Scheduler 계층이 미확정 환불을 임의로 확정하지 않는지 확인합니다.
     */
    @Test
    void successUsesUtcFiveMinuteBucketAndJsonSummary() {
        var summary = summary(4, 1, 1, 1, 0, 1, 0);
        when(work.recoverStaleRefunds(any(Runnable.class))).thenReturn(summary);
        AtomicReference<String> json = new AtomicReference<>();
        stubRunner(invocation -> {
            json.set(invocation.apply(ticket()));
            return SchedulerJobRunner.ExecutionResult.SUCCEEDED;
        });

        var result = service.normalize();
        assertEquals(BUCKET, result.scheduledFor());
        assertEquals(SchedulerJobRunner.ExecutionResult.SUCCEEDED, result.execution());
        assertSame(summary, result.summary());
        assertNull(result.error());
        assertTrue(json.get().contains("\"definitiveFailedCount\":1"));
        assertTrue(json.get().contains("\"pendingCount\":1"));
        verify(work).recoverStaleRefunds(any(Runnable.class));
    }

    /**
     * 실행권을 얻지 못하면 실제 환불 업무를 실행하지 않습니다.
     * 다른 서버의 유효한 RUNNING과 과거 SUCCEEDED를 공통 Runner가 차단합니다.
     * 결과에 존재하지 않는 환불 성공 건수를 만들어 넣지 않습니다.
     */
    @Test
    void skippedClaimDoesNotRunRefundWork() {
        stubRunner(invocation -> SchedulerJobRunner.ExecutionResult.SKIPPED);
        var result = service.normalize();
        assertEquals(SchedulerJobRunner.ExecutionResult.SKIPPED, result.execution());
        assertNull(result.summary());
        verifyNoInteractions(work, lifecycle);
    }

    /**
     * PG에서 명확히 거절된 취소는 기술적 장애가 아닙니다.
     * #22가 확정한 실패를 FAILED Scheduler 이력으로 오인하지 않게 합니다.
     * 아이템 회수 여부는 이 테스트가 아니라 #22 정합성 테스트에서 검증합니다.
     */
    @Test
    void definitivePgFailureIsNotSchedulerError() {
        when(work.recoverStaleRefunds(any(Runnable.class))).thenReturn(summary(1, 0, 1, 0, 0, 0, 0));
        stubRunner(invocation -> {
            invocation.apply(ticket());
            return SchedulerJobRunner.ExecutionResult.SUCCEEDED;
        });
        assertEquals(SchedulerJobRunner.ExecutionResult.SUCCEEDED, service.normalize().execution());
    }

    /**
     * 취소 상태가 여전히 PENDING 또는 UNKNOWN이라면 결제를 성공으로 확정하지 않습니다.
     * 단, 기술적인 예외가 없다면 이번 재조회 자체는 완료된 실행으로 기록합니다.
     * 미확정 환불은 #22가 다음 5분 점검에서 다시 조회하도록 보존해야 합니다.
     */
    @Test
    void pendingAndUnknownRemainNonterminalWithoutSchedulerFailure() {
        when(work.recoverStaleRefunds(any(Runnable.class))).thenReturn(summary(2, 0, 0, 1, 1, 0, 0));
        stubRunner(invocation -> {
            invocation.apply(ticket());
            return SchedulerJobRunner.ExecutionResult.SUCCEEDED;
        });
        assertNull(service.normalize().error());
    }

    /**
     * 외부 PG·DB 오류 때문에 처리하지 못한 건은 성공 이력으로 기록하지 않습니다.
     * #22에서 이미 독립 Commit된 결과는 롤백하지 않습니다.
     * Runner는 이 예외를 이용하여 FAILED 실행 이력을 남깁니다.
     */
    @Test
    void technicalErrorsPreventSucceededHistory() {
        when(work.recoverStaleRefunds(any(Runnable.class))).thenReturn(summary(1, 0, 0, 0, 0, 0, 1));
        stubRunner(invocation -> {
            invocation.apply(ticket());
            return SchedulerJobRunner.ExecutionResult.SUCCEEDED;
        });
        var result = service.normalize();
        assertNull(result.execution());
        assertTrue(result.error().contains("기술 오류"));
    }

    /**
     * #22 구현체가 반환한 건수의 총합이 선택 건수와 다르면 실패 처리합니다.
     * 누락된 항목을 마치 성공 처리한 것으로 운영 이력에 남기지 않습니다.
     * 실제 이력 상태 변경은 공통 Runner에 위임합니다.
     */
    @Test
    void inconsistentCountsFail() {
        when(work.recoverStaleRefunds(any(Runnable.class))).thenReturn(summary(2, 1, 0, 0, 0, 0, 0));
        stubRunner(invocation -> {
            invocation.apply(ticket());
            return SchedulerJobRunner.ExecutionResult.SUCCEEDED;
        });
        assertTrue(service.normalize().error().contains("집계"));
    }

    /**
     * 최대 처리 건수를 넘어서는 결과는 업무 계약 위반으로 거절합니다.
     * 초과 처리로 Lease가 예상보다 길게 점유되는 상황을 조기에 발견합니다.
     * 실제 목록 상한 적용은 #22 업무 Service가 구현할 책임입니다.
     */
    @Test
    void refusesMoreThanHundredResults() {
        when(work.recoverStaleRefunds(any(Runnable.class))).thenReturn(summary(101, 101, 0, 0, 0, 0, 0));
        stubRunner(invocation -> {
            invocation.apply(ticket());
            return SchedulerJobRunner.ExecutionResult.SUCCEEDED;
        });
        assertNotNull(service.normalize().error());
    }

    /**
     * 업무 서비스가 예외를 던지면 정상화 계층은 오류를 숨기지 않습니다.
     * Runner는 예외를 받아 실패 이력의 원인을 기록할 수 있어야 합니다.
     * 트리거 전체 중단은 Scheduler 경계에서 방지합니다.
     */
    @Test
    void propagatesWorkExceptionToRunner() {
        when(work.recoverStaleRefunds(any(Runnable.class))).thenThrow(new IllegalStateException("portone down"));
        stubRunner(invocation -> {
            invocation.apply(ticket());
            return SchedulerJobRunner.ExecutionResult.SUCCEEDED;
        });
        assertTrue(service.normalize().error().contains("portone down"));
    }

    /**
     * #22 업무 구현이 받은 Heartbeat를 호출하면 해당 실행권을 갱신합니다.
     * 원본 티켓과 Lease 기간을 그대로 전달하여 타 실행권을 변경하지 않습니다.
     * 실제 업무 서비스가 20건마다 호출하는지 확인하는 것은 연동 테스트 책임입니다.
     */
    @Test
    void heartbeatUsesCurrentClaimTicket() {
        when(work.recoverStaleRefunds(any(Runnable.class))).thenAnswer(invocation -> {
            Runnable heartbeat = invocation.getArgument(0);
            heartbeat.run();
            return summary(0, 0, 0, 0, 0, 0, 0);
        });
        stubRunner(invocation -> {
            invocation.apply(ticket());
            return SchedulerJobRunner.ExecutionResult.SUCCEEDED;
        });
        assertNull(service.normalize().error());
        verify(lifecycle).renewLease(eq(ticket()), eq(LEASE));
    }

    /**
     * 외부 업무 트랜잭션이 열린 상태에서는 PG 조회를 시작하지 않습니다.
     * DB 커넥션과 행 잠금을 들고 네트워크 I/O를 수행하지 않게 합니다.
     * 테스트에서 변경한 ThreadLocal 플래그는 항상 복원합니다.
     */
    @Test
    void refusesOuterTransaction() {
        boolean prior = TransactionSynchronizationManager.isActualTransactionActive();
        try {
            TransactionSynchronizationManager.setActualTransactionActive(true);
            assertThrows(IllegalStateException.class, service::normalize);
            verifyNoInteractions(runner, work);
        } finally {
            TransactionSynchronizationManager.setActualTransactionActive(prior);
        }
    }

    /**
     * 동일 JVM의 기동 트리거와 Cron이 겹치면 두 번째 호출을 즉시 건너뜁니다.
     * 첫 호출이 Runner의 업무 단계에 진입한 상태를 CountDownLatch로 재현합니다.
     * 이 테스트는 다중 서버의 서로 다른 5분 구간 경합까지 보장하지는 않습니다.
     */
    @Test
    void overlappingCallsInOneJvmAreSkipped() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        when(work.recoverStaleRefunds(any(Runnable.class))).thenAnswer(invocation -> {
            entered.countDown();
            if (!release.await(5, TimeUnit.SECONDS)) {
                throw new IllegalStateException("test barrier timeout");
            }
            return summary(0, 0, 0, 0, 0, 0, 0);
        });
        stubRunner(invocation -> {
            invocation.apply(ticket());
            return SchedulerJobRunner.ExecutionResult.SUCCEEDED;
        });
        FutureTask<RefundReconciliationNormalizationService.Result> task = new FutureTask<>(service::normalize);
        Thread worker = new Thread(task, "refund-normalization-test");
        worker.start();
        try {
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            var skipped = service.normalize();
            assertTrue(skipped.alreadyRunning());
            assertEquals(SchedulerJobRunner.ExecutionResult.SKIPPED, skipped.execution());
        } finally {
            release.countDown();
        }
        assertEquals(SchedulerJobRunner.ExecutionResult.SUCCEEDED, task.get(5, TimeUnit.SECONDS).execution());
        worker.join(1_000);
    }

    /**
     * 잘못된 음수 집계는 Record 생성 단계부터 거절합니다.
     * 운영 통계의 음수 값을 허용하지 않습니다.
     * PG 서버에 접근하지 않아도 검사할 수 있습니다.
     */
    @Test
    void rejectsNegativeSummary() {
        assertThrows(IllegalArgumentException.class, () -> summary(0, -1, 0, 0, 0, 0, 0));
    }

    /**
     * Mockito의 두 Runner 오버로드 중 티켓 전달형 Function을 정확히 선택합니다.
     * 제어된 콜백에서 실제 #24 비즈니스 람다를 한 번 실행합니다.
     * 동일한 헬퍼를 여러 경계 시나리오에서 재사용합니다.
     */
    private void stubRunner(Function<Function<SchedulerClaimTicket, String>, SchedulerJobRunner.ExecutionResult> behavior) {
        when(runner.run(eq(RefundReconciliationNormalizationService.JOB_TYPE), eq(BUCKET), anyString(),
                eq(LEASE), ArgumentMatchers.<Function<SchedulerClaimTicket, String>>any()))
                .thenAnswer(invocation -> {
                    @SuppressWarnings("unchecked")
                    Function<SchedulerClaimTicket, String> job = invocation.getArgument(4);
                    return behavior.apply(job);
                });
    }

    /**
     * 반복되는 더미 실행권을 고정값으로 생성합니다.
     * 실행권 ID, Job Type, UTC 논리 시각을 일관되게 유지합니다.
     * 특정 테스트에서 이 값을 바꾸지 않도록 합니다.
     */
    private SchedulerClaimTicket ticket() {
        return new SchedulerClaimTicket(99L, RefundReconciliationNormalizationService.JOB_TYPE,
                BUCKET, "unit-test-owner", 1);
    }

    /**
     * 테스트에서 각 환불 처리 결과의 유형을 명시적으로 구성합니다.
     * 결과 합이 선택 건수와 일치하는지는 정상화 계층이 검증합니다.
     * 부적절한 음수는 Record 생성 단계에서 거절됩니다.
     */
    private RefundRecoveryWork.RecoverySummary summary(int selected, int completed, int definitiveFailed,
            int pending, int unknown, int skipped, int errors) {
        return new RefundRecoveryWork.RecoverySummary(
                selected, completed, definitiveFailed, pending, unknown, skipped, errors);
    }
}
