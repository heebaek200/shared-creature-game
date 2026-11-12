package com.team.sharedcreature.quest.application;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 파일 I/O 중 Scheduler Lease와 단건 Claim의 독립 갱신을 검증합니다. */
class PhotoCleanupLeaseHeartbeatTest {

    private static final Instant NOW = Instant.parse("2026-11-10T09:07:45Z");
    private static final String CLAIM = "11111111-1111-1111-1111-111111111111";

    /** 백그라운드 주기가 상위 실행권과 활성 단건 Claim을 모두 갱신합니다. */
    @Test
    void periodicallyRenewsSchedulerAndActiveFileClaim() throws Exception {
        PhotoSubmissionCleanupTransactionService transactions =
                mock(PhotoSubmissionCleanupTransactionService.class);
        CountDownLatch renewed = new CountDownLatch(1);
        when(transactions.renewFileCleanupClaim(
                7L, CLAIM, NOW, NOW.plus(Duration.ofMinutes(10))))
                .thenAnswer(invocation -> {
                    renewed.countDown();
                    return true;
                });
        AtomicInteger schedulerHeartbeats = new AtomicInteger();
        PhotoCleanupLeaseHeartbeat heartbeat = new PhotoCleanupLeaseHeartbeat(
                transactions,
                Clock.fixed(NOW, ZoneOffset.UTC),
                Duration.ofMillis(10)
        );

        try (PhotoCleanupLeaseHeartbeat.Scope scope = heartbeat.start(
                schedulerHeartbeats::incrementAndGet, Duration.ofMinutes(10))) {
            scope.activate(7L, CLAIM);
            assertTrue(renewed.await(1, TimeUnit.SECONDS));
            scope.assertHealthy();
        }

        assertTrue(schedulerHeartbeats.get() >= 1);
        verify(transactions, atLeastOnce()).renewFileCleanupClaim(
                7L, CLAIM, NOW, NOW.plus(Duration.ofMinutes(10)));
    }

    /** 재선점으로 단건 Claim 갱신이 거부되면 이전 실행에 실패를 전달합니다. */
    @Test
    void rejectedClaimRenewalMarksScopeUnhealthy() throws Exception {
        PhotoSubmissionCleanupTransactionService transactions =
                mock(PhotoSubmissionCleanupTransactionService.class);
        CountDownLatch attempted = new CountDownLatch(1);
        when(transactions.renewFileCleanupClaim(anyLong(), anyString(), any(), any()))
                .thenAnswer(invocation -> {
                    attempted.countDown();
                    return false;
                });
        PhotoCleanupLeaseHeartbeat heartbeat = new PhotoCleanupLeaseHeartbeat(
                transactions,
                Clock.fixed(NOW, ZoneOffset.UTC),
                Duration.ofMillis(10)
        );
        PhotoCleanupLeaseHeartbeat.Scope scope = heartbeat.start(
                () -> { }, Duration.ofMinutes(10));
        scope.activate(8L, CLAIM);

        assertTrue(attempted.await(1, TimeUnit.SECONDS));
        assertThrows(IllegalStateException.class, scope::assertHealthy);
        assertThrows(IllegalStateException.class, scope::close);
    }
}
