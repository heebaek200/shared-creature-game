package com.team.sharedcreature.payment.application;

import com.team.sharedcreature.admin.application.SchedulerClaimTicket;
import com.team.sharedcreature.admin.application.SchedulerJobRunner;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

/**
 * #21의 결제 Webhook 업무 복구를 #24의 Scheduler 실행권 체계에 연결합니다.
 * 서버 재기동과 5분 Cron은 현재 UTC 5분 구간만 처리하고 과거 틱은 생성하지 않습니다.
 * PortOne 조회, 거래 확정, 원장 보존은 기존 PaymentWebhookRecoveryService가 담당합니다.
 * 장시간 PG 대기 중에도 주기적인 DB Lease 갱신을 별도 스레드에서 수행합니다.
 */
@Slf4j
@Service
public class PaymentWebhookReconciliationNormalizationService {

    public static final String JOB_TYPE = "PAYMENT_WEBHOOK_RECONCILIATION";
    private static final long INTERVAL_SECONDS = 300L;
    private static final Duration LEASE = Duration.ofMinutes(3);

    private final SchedulerJobRunner jobRunner;
    private final PaymentWebhookRecoveryService recoveryService;
    private final PaymentWebhookLeaseHeartbeat heartbeat;
    private final Clock clock;
    private final String ownerId = "payment-webhook-" + UUID.randomUUID();
    private final AtomicBoolean running = new AtomicBoolean(false);

    /**
     * 실행권, #21 복구 서비스, 공통 Clock을 주입받습니다.
     * 장시간 결제 조회 중에도 살아 있는 실행권을 유지할 Heartbeat를 주입받습니다.
     * 생성자에서는 스케줄 실행이나 외부 PG 호출을 수행하지 않습니다.
     */
    public PaymentWebhookReconciliationNormalizationService(
            SchedulerJobRunner jobRunner,
            PaymentWebhookRecoveryService recoveryService,
            PaymentWebhookLeaseHeartbeat heartbeat,
            Clock clock
    ) {
        this.jobRunner = Objects.requireNonNull(jobRunner, "jobRunner");
        this.recoveryService = Objects.requireNonNull(recoveryService, "recoveryService");
        this.heartbeat = Objects.requireNonNull(heartbeat, "heartbeat");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /**
     * 한 번의 정리 시도와 실행권 결과를 나타냅니다.
     * SKIPPED는 이미 처리된 구간 또는 다른 인스턴스에서 진행 중인 구간을 의미합니다.
     * alreadyRunning은 같은 JVM에서 이전 작업이 끝나지 않아 건너뛴 경우만 나타냅니다.
     */
    public record Result(
            Instant scheduledFor,
            SchedulerJobRunner.ExecutionResult execution,
            PaymentWebhookRecoveryService.PaymentWebhookRecoveryResult summary,
            String error,
            boolean alreadyRunning
    ) { }

    /**
     * 현재 UTC 5분 논리 구간의 결제 Webhook 복구를 실행합니다.
     * 실패 건수가 하나라도 있으면 Scheduler 이력을 SUCCEEDED로 확정하지 않습니다.
     * 다른 결제 상태 복구나 환불 취소 API는 이 메서드에서 수행하지 않습니다.
     * Heartbeat 갱신 실패는 성공을 막고 Scheduler 실패로 기록합니다.
     */
    public Result normalize() {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("결제 Webhook 복구는 외부 DB 트랜잭션 밖에서 시작해야 합니다.");
        }

        Instant now = Instant.now(clock);
        Instant scheduledFor = Instant.ofEpochSecond(
                Math.floorDiv(now.getEpochSecond(), INTERVAL_SECONDS) * INTERVAL_SECONDS);
        if (!running.compareAndSet(false, true)) {
            return new Result(scheduledFor, SchedulerJobRunner.ExecutionResult.SKIPPED,
                    null, null, true);
        }

        AtomicReference<PaymentWebhookRecoveryService.PaymentWebhookRecoveryResult> summary =
                new AtomicReference<>();
        try {
            // 선점된 티켓으로 Heartbeat를 구동해 PG 조회 중에도 Lease를 연장합니다.
            SchedulerJobRunner.ExecutionResult execution = jobRunner.run(
                    JOB_TYPE, scheduledFor, ownerId, LEASE,
                    (Function<SchedulerClaimTicket, String>) ticket -> {
                        // PG 조회가 블로킹되더라도 별도 스레드가 DB Lease를 갱신합니다.
                        try (PaymentWebhookLeaseHeartbeat.Scope scope = heartbeat.start(ticket, LEASE)) {
                            var outcome = Objects.requireNonNull(
                                    recoveryService.recoverStaleWebhooks(), "결제 Webhook 복구 결과가 없습니다.");
                            summary.set(outcome);
                            validateSummary(outcome);
                            if (outcome.failedCount() > 0) {
                                throw new IllegalStateException(
                                        "결제 Webhook 복구 실패 건수: " + outcome.failedCount());
                            }
                            // try-with-resources가 Heartbeat를 완전히 닫은 후 성공 요약을 반환합니다.
                            scope.assertHealthy();
                            return toJson(outcome);
                        }
                    });
            return new Result(scheduledFor, execution, summary.get(), null, false);
        } catch (RuntimeException failure) {
            // 원래 오류는 SchedulerJobRunner가 FAILED 이력에 기록하도록 전파된 후 여기서 격리합니다.
            log.error("결제 Webhook Scheduler 실행 실패. scheduledFor={}", scheduledFor, failure);
            return new Result(scheduledFor, null, summary.get(), failure.getMessage(), false);
        } finally {
            running.set(false);
        }
    }

    /**
     * #21의 최대 100건 배치 계약과 결과 합계의 정합성을 검사합니다.
     * 잘못된 결과를 SUCCEEDED로 기록하면 운영상 누락을 발견하기 어려워집니다.
     * 검증 실패는 Runner의 일반 업무 실패 경로로 전달합니다.
     */
    private void validateSummary(PaymentWebhookRecoveryService.PaymentWebhookRecoveryResult outcome) {
        int selected = outcome.selectedCount();
        int processed = outcome.processedCount();
        int skipped = outcome.skippedCount();
        int failed = outcome.failedCount();
        if (selected < 0 || selected > 100 || processed < 0 || skipped < 0 || failed < 0
                || (long) processed + skipped + failed != selected) {
            throw new IllegalStateException("결제 Webhook 복구 결과 건수가 일치하지 않습니다.");
        }
    }

    /**
     * 운영 실행 이력의 result_summary에 저장할 안전한 JSON 문자열을 만듭니다.
     * 임의 문자열이나 Webhook Payload를 포함하지 않고 집계 숫자만 기록합니다.
     * 각 숫자 필드는 #21에서 제공한 결과 계약을 그대로 따릅니다.
     */
    private String toJson(PaymentWebhookRecoveryService.PaymentWebhookRecoveryResult outcome) {
        return """
                {"selectedCount":%d,"processedCount":%d,"skippedCount":%d,"failedCount":%d}
                """.formatted(outcome.selectedCount(), outcome.processedCount(),
                outcome.skippedCount(), outcome.failedCount()).trim();
    }
}
