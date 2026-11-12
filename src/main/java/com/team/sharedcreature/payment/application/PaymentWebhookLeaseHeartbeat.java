package com.team.sharedcreature.payment.application;

import com.team.sharedcreature.admin.application.SchedulerClaimTicket;
import com.team.sharedcreature.admin.application.SchedulerExecutionLifecycleService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * PortOne 조회 중에도 Scheduler Lease를 주기적으로 연장합니다.
 * Heartbeat는 별도 스레드의 짧은 REQUIRES_NEW 트랜잭션으로 실행됩니다.
 * 종료 시 갱신 스레드를 완전히 정지시킨 뒤 Runner가 성공을 기록하도록 합니다.
 */
@Component
public class PaymentWebhookLeaseHeartbeat {

    private static final Duration DEFAULT_INTERVAL = Duration.ofSeconds(30);

    private final SchedulerExecutionLifecycleService lifecycle;
    private final Duration interval;

    /**
     * 운영에서는 30초마다 실행권을 갱신합니다.
     * 업무 스레드에서 PortOne API가 지연되어도 갱신 스레드는 독립적으로 동작합니다.
     * 실제 Lease의 소유자·시도 번호는 기존 LifecycleService에서 확인합니다.
     */
    @Autowired
    public PaymentWebhookLeaseHeartbeat(SchedulerExecutionLifecycleService lifecycle) {
        this(lifecycle, DEFAULT_INTERVAL);
    }

    /**
     * 테스트용으로 작은 Heartbeat 간격을 주입할 수 있도록 제공합니다.
     * 운영 DI에서는 위 단일 인자 생성자를 사용합니다.
     * 간격을 0이나 음수로 지정하면 즉시 거부합니다.
     */
    public PaymentWebhookLeaseHeartbeat(SchedulerExecutionLifecycleService lifecycle, Duration interval) {
        this.lifecycle = Objects.requireNonNull(lifecycle, "lifecycle");
        this.interval = Objects.requireNonNull(interval, "interval");
        if (interval.isZero() || interval.isNegative() || interval.toMillis() < 1) {
            throw new IllegalArgumentException("Heartbeat 간격은 1ms 이상이어야 합니다.");
        }
    }

    /**
     * 실행권을 얻은 작업에만 독립적인 Heartbeat 스레드를 부착합니다.
     * 스레드는 daemon으로 만들고 Scope가 종료되면 즉시 정리합니다.
     * 첫 갱신은 설정한 주기가 지난 뒤 시작해 불필요한 초기 갱신을 피합니다.
     */
    public Scope start(SchedulerClaimTicket ticket, Duration leaseDuration) {
        Objects.requireNonNull(ticket, "ticket");
        Objects.requireNonNull(leaseDuration, "leaseDuration");
        if (leaseDuration.isZero() || leaseDuration.isNegative() || !interval.minus(leaseDuration).isNegative()) {
            throw new IllegalArgumentException("Heartbeat 주기는 Lease보다 짧아야 합니다.");
        }

        ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor(task -> {
            Thread thread = new Thread(task, "payment-webhook-lease-heartbeat");
            thread.setDaemon(true);
            return thread;
        });
        Scope scope = new Scope(executor);
        long periodMillis = interval.toMillis();
        try {
            ScheduledFuture<?> scheduled = executor.scheduleWithFixedDelay(() -> {
                if (scope.failure.get() != null || scope.closed.get()) {
                    return;
                }
                try {
                    // 별도 트랜잭션에서 DB 실행권의 현재 소유자와 시도 번호를 검증합니다.
                    lifecycle.renewLease(ticket, leaseDuration);
                } catch (RuntimeException exception) {
                    scope.failure.compareAndSet(null, exception);
                }
            }, periodMillis, periodMillis, TimeUnit.MILLISECONDS);
            scope.scheduled = scheduled;
            return scope;
        } catch (RuntimeException exception) {
            executor.shutdownNow();
            throw exception;
        }
    }

    /**
     * 실행권 갱신 스레드의 수명을 업무 실행 범위로 제한합니다.
     * 외부 예외가 없어도 Heartbeat 실패가 있으면 정상 성공으로 종료하지 않습니다.
     * close가 완료된 뒤에는 더 이상 DB 갱신을 수행하지 않습니다.
     */
    public static final class Scope implements AutoCloseable {
        private final ScheduledExecutorService executor;
        private final AtomicReference<RuntimeException> failure = new AtomicReference<>();
        private final AtomicBoolean closed = new AtomicBoolean(false);
        private ScheduledFuture<?> scheduled;

        private Scope(ScheduledExecutorService executor) {
            this.executor = executor;
        }

        /**
         * 주기적 Heartbeat 실패를 원래 RuntimeException으로 전달합니다.
         * 이 예외는 SchedulerJobRunner에서 FAILED 기록을 시도하는 근거가 됩니다.
         * 정상적인 실행에서는 호출한 스레드에 영향을 주지 않습니다.
         */
        public void assertHealthy() {
            RuntimeException error = failure.get();
            if (error != null) {
                throw new IllegalStateException("결제 Webhook Scheduler Lease 갱신에 실패했습니다.", error);
            }
        }

        /**
         * Heartbeat 완료를 기다린 후 스레드를 종료하고 갱신 오류를 확인합니다.
         * Runner는 이 메서드가 완료된 뒤에만 SUCCEEDED를 기록할 수 있습니다.
         * 종료 중 인터럽트는 복원해 상위 실행기를 실패 경로로 유도합니다.
         */
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
                // 이미 실행 중인 갱신 트랜잭션이 끝나야 이력 성공 처리와 경합하지 않습니다.
                if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
                    executor.shutdownNow();
                    throw new IllegalStateException("Heartbeat 종료가 5초를 초과했습니다.");
                }
            } catch (InterruptedException exception) {
                executor.shutdownNow();
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Heartbeat 종료가 인터럽트되었습니다.", exception);
            }
            assertHealthy();
        }
    }
}
