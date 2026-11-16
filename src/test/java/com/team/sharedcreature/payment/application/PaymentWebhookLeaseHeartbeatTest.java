package com.team.sharedcreature.payment.application;

import com.team.sharedcreature.admin.application.SchedulerClaimTicket;
import com.team.sharedcreature.admin.application.SchedulerExecutionLifecycleService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * 별도 스레드의 DB Lease 연장과 종료·오류 전파를 검증합니다.
 * 실제 PG 통신을 발생시키지 않고 짧은 Heartbeat 간격을 사용합니다.
 * 실행권의 MySQL Commit까지 검증하는 테스트는 별도 통합 테스트입니다.
 */
class PaymentWebhookLeaseHeartbeatTest {

    private final SchedulerExecutionLifecycleService lifecycle = mock(SchedulerExecutionLifecycleService.class);
    private final SchedulerClaimTicket ticket = new SchedulerClaimTicket(1L,
            "PAYMENT_WEBHOOK_RECONCILIATION", Instant.parse("2026-11-11T01:05:00Z"), "owner-a", 1);

    /**
     * PG 업무 스레드가 대기 중이어도 별도 Heartbeat 스레드가 동작합니다.
     * CountDownLatch로 갱신 호출을 관찰하고 정상 종료를 검사합니다.
     * 사용한 주기는 테스트 범위에서만 10ms로 단축합니다.
     */
    @Test
    @Timeout(5)
    void backgroundHeartbeatRunsWithoutBlockingWorker() throws Exception {
        CountDownLatch called = new CountDownLatch(1);
        doAnswer(invocation -> { called.countDown(); return null; })
                .when(lifecycle).renewLease(eq(ticket), eq(Duration.ofMinutes(3)));
        var helper = new PaymentWebhookLeaseHeartbeat(lifecycle, Duration.ofMillis(10));

        try (var scope = helper.start(ticket, Duration.ofMinutes(3))) {
            assertTrue(called.await(2, TimeUnit.SECONDS));
            assertDoesNotThrow(scope::assertHealthy);
        }
        verify(lifecycle, atLeastOnce()).renewLease(ticket, Duration.ofMinutes(3));
    }

    /**
     * 갱신 실행 중 발생한 DB 예외는 별도 스레드에서 소실되어서는 안 됩니다.
     * Scope 종료가 예외를 반환하여 Runner가 FAILED로 기록하도록 합니다.
     * 오류를 확인하기 전까지 기다릴 수 있도록 Latch를 사용합니다.
     */
    @Test
    @Timeout(5)
    void heartbeatFailureBlocksSuccessfulCompletion() throws Exception {
        CountDownLatch failed = new CountDownLatch(1);
        doAnswer(invocation -> {
            failed.countDown();
            throw new IllegalStateException("database unavailable");
        }).when(lifecycle).renewLease(eq(ticket), eq(Duration.ofMinutes(3)));
        var helper = new PaymentWebhookLeaseHeartbeat(lifecycle, Duration.ofMillis(10));
        var scope = helper.start(ticket, Duration.ofMinutes(3));

        assertTrue(failed.await(2, TimeUnit.SECONDS));
        IllegalStateException error = assertThrows(IllegalStateException.class, scope::close);
        assertTrue(error.getMessage().contains("갱신에 실패"));
        assertEquals("database unavailable", error.getCause().getMessage());
    }

    /**
     * 정상 종료 이후에는 예약된 갱신 호출을 남겨 두지 않습니다.
     * 종료를 두 번 호출해도 추가 작업을 시작하지 않아야 합니다.
     * 이 검증은 갱신 스레드가 완료되기 전 타이밍 경합을 피합니다.
     */
    @Test
    void closeIsIdempotentAndRejectsNoWork() {
        var helper = new PaymentWebhookLeaseHeartbeat(lifecycle, Duration.ofHours(1));
        var scope = helper.start(ticket, Duration.ofHours(2));
        assertDoesNotThrow(scope::close);
        assertDoesNotThrow(scope::close);
        verifyNoInteractions(lifecycle);
    }

    /**
     * 잘못된 주기와 Lease의 조합을 실행 전 거절합니다.
     * 만료 후 첫 갱신을 시도하는 구성은 복구 동작을 보장하지 않습니다.
     * 이 검증은 스레드 생성과 DB 호출 없이 종료됩니다.
     */
    @Test
    void rejectsIntervalNotShorterThanLease() {
        var helper = new PaymentWebhookLeaseHeartbeat(lifecycle, Duration.ofSeconds(30));
        assertThrows(IllegalArgumentException.class,
                () -> helper.start(ticket, Duration.ofSeconds(30)));
        verifyNoInteractions(lifecycle);
    }
}
