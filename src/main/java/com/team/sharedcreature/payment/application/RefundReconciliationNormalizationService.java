package com.team.sharedcreature.payment.application;

import com.team.sharedcreature.admin.application.SchedulerClaimTicket;
import com.team.sharedcreature.admin.application.SchedulerExecutionLifecycleService;
import com.team.sharedcreature.admin.application.SchedulerJobRunner;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 환불 복구의 실행권 및 결과 이력 오케스트레이터.
 * (추후 환불 업무 구현 시 조율 필요) 인터페이스 RefundRecoveryWork를 호출
 * 실제 결제, 환불 판정, PortOne 통신, 원장 수정은 수행하지 않는다.
 */
@Slf4j
@Service
@Profile("!test")
@ConditionalOnProperty(
        prefix = "app.scheduler",
        name = {
                "enabled",
                "refund-reconciliation-enabled"
        },
        havingValue = "true"
)
public class RefundReconciliationNormalizationService {

    public static final String JOB_TYPE = "REFUND_RECONCILIATION";
    private static final long FIVE_MINUTES_SECONDS = 300L;
    private static final int MAX_BATCH_SIZE = 100;
    private static final Duration LEASE = Duration.ofMinutes(3);

    private final SchedulerJobRunner runner;
    private final SchedulerExecutionLifecycleService lifecycle;
    private final RefundRecoveryWork refundWork;
    private final Clock clock;

    private final String ownerId = "refund-recovery-" + UUID.randomUUID();
    private final AtomicBoolean running = new AtomicBoolean(false);

    public RefundReconciliationNormalizationService(
            SchedulerJobRunner runner,
            SchedulerExecutionLifecycleService lifecycle,
            RefundRecoveryWork refundWork,
            Clock clock
    ) {
        this.runner = Objects.requireNonNull(runner, "runner");
        this.lifecycle = Objects.requireNonNull(lifecycle, "lifecycle");
        this.refundWork = Objects.requireNonNull(refundWork, "refundWork");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /**
     * 단일 실행의 결과 및 생략, 실패 여부를 표현하는 레코드.
     * 업무 예외가 발생하면 SchedulerJobRunner가 FAILED 이력을 기록함.
     * alreadyRunning은 동일 JVM에서 이전 회차가 진행 중인 경우에만 true.
     */
    public record Result(
            Instant scheduledFor,
            SchedulerJobRunner.ExecutionResult execution,
            RefundRecoveryWork.RecoverySummary summary,
            String error,
            boolean alreadyRunning
    ) {}

    /**
     * 현재 UTC 5분 구간의 환불 고착 상태를 한 번 정상화한다.
     * 실제 취소를 재요청하지 않으며 업무 서비스에 최신 상태 조회를 위임한다.
     * 결과의 기술적 실패는 실행 이력 FAILED로 처리하되 확정된 실패는 성공 건수로 기록한다.
     */
    public Result normalize() {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("환불 복구는 외부 DB 트랜잭션 밖에서 시작해야 합니다.");
        }

        Instant now = clock.instant();
        Instant scheduledFor = Instant.ofEpochSecond(
                Math.floorDiv(now.getEpochSecond(), FIVE_MINUTES_SECONDS) * FIVE_MINUTES_SECONDS
        );
        if (! running.compareAndSet(false, true)) {
            return new Result(scheduledFor, SchedulerJobRunner.ExecutionResult.SKIPPED, null, null, true);
        }

        AtomicReference<RefundRecoveryWork.RecoverySummary> summary = new AtomicReference<>();
        try {
            // 실행권의 시도 번호를 갖는 티켓을 Heartbeat 콜백과 연결한다.
            SchedulerJobRunner.ExecutionResult execution = runner.run(
                    JOB_TYPE, scheduledFor, ownerId, LEASE, (SchedulerClaimTicket ticket) -> {

                        Runnable heartbeat = () -> lifecycle.renewLease(ticket, LEASE);
                        RefundRecoveryWork.RecoverySummary outcome = Objects.requireNonNull(
                                refundWork.recoverStaleRefunds(heartbeat), "환불 복구 결과가 없습니다."
                        );

                        summary.set(outcome);
                        validateSummary(outcome);

                        if (outcome.errorCount() != 0) {
                            throw new IllegalStateException("환불 복구 기술 오류: " + outcome.errorCount());
                        }

                        return toJson(outcome);
                    }
            );

            return new Result(scheduledFor, execution, summary.get(), null, false);
        } catch (RuntimeException e) {
            // 원인 예외는 실행 이력 기록 후 로깅하고 다음 스케쥴 구간의 재시도를 허용한다
            log.error("환불 복구 Scheduler 실패. scheduledFor={}", scheduledFor, e);
            return new Result(scheduledFor, null, summary.get(), e.getMessage(), false);
        } finally {
            running.set(false);
        }
    }

    /**
     * 어댑터가 반환한 결과의 산한 및 분류 합계를 검사한다.
     * 최종 PG 결과는 정상 확정 결과이며 errorCount와 혼동하지 않는다.
     * 누락, 중복 집계를 성공 이력으로 기록하지 않도록 숫자 합을 확인한다.
     */
    private void validateSummary(RefundRecoveryWork.RecoverySummary outcome) {
        long total = (long)
                outcome.completedCount() +
                outcome.definitiveFailedCount() +
                outcome.pendingCount() +
                outcome.unknownCount() +
                outcome.skippedCount() +
                outcome.errorCount();

        if (outcome.selectedCount() > MAX_BATCH_SIZE || total != outcome.selectedCount()) {
            throw new IllegalStateException("환불 복구 집계가 일치하지 않습니다.");
        }
    }

    /**
     * Scheduler 실행 결과에 필요한 숫자 집계만 JSON 으로 보존한다.
     * 환불 사유, 고객 식별자, PG 원문 응답은 실행 요약에 포함하지 않는다.
     * 반호나 문자열은 scheduler_executions.result_summary 컬럼에 기록된다.
     */
    private String toJson(RefundRecoveryWork.RecoverySummary value) {
        return """
                {"selectedCount":%d,"completedCount":%d,"definitiveFailedCount":%d, "pendingCount":%d,"unknownCount":%d,"skippedCount":%d,"errorCount":%d}
                """
                .formatted(
                        value.selectedCount(),
                        value.completedCount(),
                        value.definitiveFailedCount(),
                        value.pendingCount(),
                        value.unknownCount(),
                        value.skippedCount(),
                        value.errorCount()
                ).trim();
    }

}
