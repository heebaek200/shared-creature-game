package com.team.sharedcreature.quest.application;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 사진 파일 I/O 중 Scheduler Lease와 현재 단건 claim을 함께 갱신합니다.
 * 갱신은 별도 스레드의 짧은 트랜잭션에서 수행하고 파일 I/O 트랜잭션은 열지 않습니다.
 */
@Component
public class PhotoCleanupLeaseHeartbeat {

    private static final Duration DEFAULT_INTERVAL = Duration.ofSeconds(30);

    private final PhotoSubmissionCleanupTransactionService transactions;
    private final Clock clock;
    private final Duration interval;

    /** 운영에서는 30초 주기로 실행권과 현재 파일 claim을 갱신합니다. */
    @Autowired
    public PhotoCleanupLeaseHeartbeat(PhotoSubmissionCleanupTransactionService transactions, Clock clock) {
        this(transactions, clock, DEFAULT_INTERVAL);
    }

    /** 테스트에서 실제 대기 없이 갱신 경계를 제어할 수 있도록 주기를 주입받습니다. */
    public PhotoCleanupLeaseHeartbeat(
            PhotoSubmissionCleanupTransactionService transactions,
            Clock clock,
            Duration interval
    ) {
        this.transactions = Objects.requireNonNull(transactions, "transactions");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.interval = Objects.requireNonNull(interval, "interval");
        if (interval.isZero() || interval.isNegative() || interval.toMillis() < 1) {
            throw new IllegalArgumentException("Heartbeat 간격은 1ms 이상이어야 합니다.");
        }
    }

    /**
     * 한 번의 사진 정리 실행에 Heartbeat Scope를 연결합니다.
     * 단건 claim은 파일 처리 직전에 Scope에 등록하고 처리 직후 해제합니다.
     */
    public Scope start(Runnable schedulerHeartbeat, Duration claimLease) {
        Objects.requireNonNull(schedulerHeartbeat, "schedulerHeartbeat");
        Objects.requireNonNull(claimLease, "claimLease");
        if (claimLease.isZero() || claimLease.isNegative() || !interval.minus(claimLease).isNegative()) {
            throw new IllegalArgumentException("Heartbeat 주기는 claim Lease보다 짧아야 합니다.");
        }

        ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor(task -> {
            Thread thread = new Thread(task, "photo-cleanup-lease-heartbeat");
            thread.setDaemon(true);
            return thread;
        });
        Scope scope = new Scope(executor, schedulerHeartbeat, claimLease);
        long periodMillis = interval.toMillis();
        try {
            scope.scheduled = executor.scheduleWithFixedDelay(
                    scope::renewSafely, periodMillis, periodMillis, TimeUnit.MILLISECONDS);
            return scope;
        } catch (RuntimeException exception) {
            executor.shutdownNow();
            throw exception;
        }
    }

    /** 실행 범위와 현재 단건 claim의 갱신 상태를 관리합니다. */
    public final class Scope implements AutoCloseable {
        private final ScheduledExecutorService executor;
        private final Runnable schedulerHeartbeat;
        private final Duration claimLease;
        private final AtomicReference<Claim> activeClaim = new AtomicReference<>();
        private final AtomicReference<RuntimeException> failure = new AtomicReference<>();
        private final AtomicBoolean closed = new AtomicBoolean(false);
        private ScheduledFuture<?> scheduled;

        private Scope(
                ScheduledExecutorService executor,
                Runnable schedulerHeartbeat,
                Duration claimLease
        ) {
            this.executor = executor;
            this.schedulerHeartbeat = schedulerHeartbeat;
            this.claimLease = claimLease;
        }

        /** 파일 I/O를 시작할 단건 claim을 Heartbeat 대상으로 등록합니다. */
        public void activate(Long submissionId, String claimToken) {
            assertHealthy();
            Claim claim = new Claim(
                    Objects.requireNonNull(submissionId, "submissionId"),
                    Objects.requireNonNull(claimToken, "claimToken"));
            if (!activeClaim.compareAndSet(null, claim)) {
                throw new IllegalStateException("이미 갱신 중인 사진 claim이 있습니다.");
            }
        }

        /** 현재 실행이 등록한 단건 claim만 Heartbeat 대상에서 해제합니다. */
        public void deactivate(Long submissionId, String claimToken) {
            Claim current = activeClaim.get();
            if (current != null
                    && Objects.equals(current.submissionId(), submissionId)
                    && Objects.equals(current.claimToken(), claimToken)) {
                activeClaim.compareAndSet(current, null);
            }
        }

        /** 파일 삭제 직후 동기 갱신으로 Scheduler와 단건 claim 소유권을 확인합니다. */
        public void renewNow() {
            assertHealthy();
            renew();
            assertHealthy();
        }

        /** 백그라운드 갱신 실패를 업무 스레드에 전달합니다. */
        public void assertHealthy() {
            RuntimeException error = failure.get();
            if (error != null) {
                throw new IllegalStateException("사진 정리 Lease 갱신에 실패했습니다.", error);
            }
        }

        private void renewSafely() {
            if (closed.get() || failure.get() != null) {
                return;
            }
            try {
                renew();
            } catch (RuntimeException exception) {
                failure.compareAndSet(null, exception);
            }
        }

        private void renew() {
            schedulerHeartbeat.run();
            Claim claim = activeClaim.get();
            if (claim == null) {
                return;
            }
            Instant controlNow = clock.instant();
            boolean renewed = transactions.renewFileCleanupClaim(
                    claim.submissionId(),
                    claim.claimToken(),
                    controlNow,
                    controlNow.plus(claimLease));
            if (!renewed) {
                throw new IllegalStateException("사진 정리 claim 소유권을 상실했습니다. submissionId="
                        + claim.submissionId());
            }
        }

        /** 갱신 스레드를 종료한 뒤 누적된 Lease 오류를 확인합니다. */
        @Override
        public void close() {
            if (!closed.compareAndSet(false, true)) {
                assertHealthy();
                return;
            }
            if (scheduled != null) {
                scheduled.cancel(false);
            }
            executor.shutdown();
            try {
                if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
                    executor.shutdownNow();
                    throw new IllegalStateException("사진 정리 Heartbeat 종료가 5초를 초과했습니다.");
                }
            } catch (InterruptedException exception) {
                executor.shutdownNow();
                Thread.currentThread().interrupt();
                throw new IllegalStateException("사진 정리 Heartbeat 종료가 인터럽트되었습니다.", exception);
            }
            assertHealthy();
        }
    }

    private record Claim(Long submissionId, String claimToken) { }
}
