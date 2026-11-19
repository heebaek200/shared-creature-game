package com.team.sharedcreature.payment.application;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import com.team.sharedcreature.payment.application.RefundApprovalTransactionService.CommandResponse;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 환불 복구 준비, PG 최신 조회, 결과 저장을 연결합니다.
 * 기존 Scheduler가 전달한 Heartbeat로 실행권을 갱신합니다.
 */
@Slf4j
@RequiredArgsConstructor
@Service
public class RefundReconciliationService implements RefundRecoveryWork {

    private final RefundRecoveryPreparationService preparationService;
    private final RefundLookupGateway lookupGateway;
    private final RefundResultTransactionService resultTransactionService;
    private final RefundReconciliationPreparationService reconciliationPreparationService;

    /**
     * 5분 이상 지난 미확정 환불을 최대 100건 복구합니다.
     * 전체 작업을 하나의 DB 트랜잭션으로 묶지 않습니다.
     */
    @Override
    @Transactional(propagation = Propagation.NEVER)
    public RecoverySummary recoverStaleRefunds(Runnable heartbeat) {
        Objects.requireNonNull(heartbeat, "heartbeat");

        // 최초 실행권 갱신에 실패하면 업무를 시작하지 않습니다.
        heartbeat.run();

        AtomicReference<RuntimeException> heartbeatFailure = new AtomicReference<>();

        // 장시간 PG 조회·DB 대기 중에도 시간 기준으로 Heartbeat를 호출합니다.
        ScheduledExecutorService heartbeatExecutor =
                Executors.newSingleThreadScheduledExecutor();

        ScheduledFuture<?> heartbeatTask = heartbeatExecutor.scheduleWithFixedDelay(
                () -> {
                    if (heartbeatFailure.get() != null) {
                        return;
                    }

                    try {
                        heartbeat.run();
                    } catch (RuntimeException exception) {
                        heartbeatFailure.compareAndSet(null, exception);
                    }
                },
                30,
                30,
                TimeUnit.SECONDS
        );

        try {
            List<Long> candidates = preparationService.findScheduledCandidates();

            checkHeartbeat(heartbeatFailure);

            int completed = 0;
            int definitiveFailed = 0;
            int pending = 0;
            int unknown = 0;
            int skipped = 0;
            int errors = 0;

            for (Long requestId : candidates) {
                checkHeartbeat(heartbeatFailure);

                try {
                    // 별도 트랜잭션에서 조건 재검사와 선점을 Commit합니다.
                    Optional<RefundRecoveryPreparationService.RecoveryCommand> prepared =
                            preparationService.prepareScheduled(requestId);

                    checkHeartbeat(heartbeatFailure);

                    if (prepared.isEmpty()) {
                        skipped++;
                    } else {
                        RefundRecoveryPreparationService.RecoveryCommand command = prepared.get();

                        PaymentSnapshot payment = null;
                        boolean lookupFailed = false;

                        try {
                            // 이 구간에는 DB 트랜잭션이 없습니다.
                            payment = lookupGateway.getPayment(
                                    command.paymentId()
                            );
                        } catch (PaymentGatewayException exception) {
                            lookupFailed = true;

                            // PG 응답 원문과 인증 정보는 로그에 남기지 않습니다.
                            log.warn(
                                    "환불 PG 조회 실패: refundRequestId={}, kind={}",
                                    requestId,
                                    exception.kind()
                            );
                        }

                        checkHeartbeat(heartbeatFailure);

                        // 조회 실패도 UNKNOWN으로 저장하되 기술 오류로 집계합니다.
                        Optional<RefundGateway.Outcome> outcome = resultTransactionService.applyRecovery(
                                command,
                                payment
                        );

                        if (lookupFailed) {
                            errors++;
                        } else if (outcome.isEmpty()) {
                            skipped++;
                        } else {
                            switch (outcome.get()) {
                                case SUCCESS -> completed++;
                                case DEFINITIVE_FAILURE -> definitiveFailed++;
                                case PENDING -> pending++;
                                case UNKNOWN -> unknown++;
                            }
                        }
                    }
                } catch (RuntimeException exception) {
                    // Heartbeat 실패라면 일반 단건 오류로 삼키지 않습니다.
                    checkHeartbeat(heartbeatFailure);

                    errors++;

                    log.warn(
                            "환불 복구 실패: refundRequestId={}, exceptionType={}",
                            requestId,
                            exception.getClass().getSimpleName()
                    );
                }

                // 한 건이 실패해도 다음 건을 처리하되 실행권 상실은 중단합니다.
                checkHeartbeat(heartbeatFailure);
            }

            checkHeartbeat(heartbeatFailure);

            return new RecoverySummary(
                    candidates.size(),
                    completed,
                    definitiveFailed,
                    pending,
                    unknown,
                    skipped,
                    errors
            );
        } finally {
            // 정상 종료와 예외 종료 모두 Heartbeat 작업을 정리합니다.
            heartbeatTask.cancel(true);
            heartbeatExecutor.shutdownNow();
        }
    }

    /**
     * 관리자 수동 복구의 준비·PG 최신 조회·결과 저장을 연결합니다.
     * 취소 요청을 다시 보내지 않고 기존 취소 결과만 확인합니다.
     *
     * @param adminUserId 요청한 관리자 ID
     * @param requestId 환불 요청 ID
     * @param idempotencyKey REST 요청 멱등 UUID
     * @param traceId 현재 요청의 추적 ID
     * @return 최초 HTTP 상태와 응답
     */
    @Transactional(propagation = Propagation.NEVER)
    public CommandResponse reconcileRefund(
            Long adminUserId,
            Long requestId,
            String idempotencyKey,
            String traceId
    ) {
        // 별도 트랜잭션에서 멱등 기록과 조회 준비를 Commit합니다.
        RefundReconciliationPreparationService.Preparation prepared = reconciliationPreparationService.prepare(
                adminUserId,
                requestId,
                idempotencyKey,
                traceId
        );

        // 저장된 응답이나 즉시 응답이 있으면 PG를 호출하지 않습니다.
        if (prepared.response() != null) {
            return prepared.response();
        }

        RefundRecoveryPreparationService.RecoveryCommand command = prepared.command();

        PaymentSnapshot payment = null;

        try {
            // 준비 트랜잭션이 종료된 상태에서 최신 결제를 조회합니다.
            payment = lookupGateway.getPayment(command.paymentId());
        } catch (PaymentGatewayException exception) {
            // 통신 실패를 취소 실패로 판단하지 않습니다.
            log.warn(
                    "관리자 환불 PG 조회 실패: refundRequestId={}, kind={}",
                    requestId,
                    exception.kind()
            );
        }

        // 조회 실패 시 null을 전달하여 UNKNOWN으로 처리합니다.
        // 환불 결과와 최초 HTTP 응답은 별도 트랜잭션에서 함께 저장합니다.
        return resultTransactionService.applyManualRecovery(
                command,
                prepared.idempotencyRecordId(),
                payment,
                traceId
        );
    }

    /** Heartbeat 실패를 호출자에게 전달하여 성공 이력 기록을 막습니다. */
    private void checkHeartbeat(
            AtomicReference<RuntimeException> heartbeatFailure
    ) {
        RuntimeException failure = heartbeatFailure.get();

        if (failure != null) {
            throw new IllegalStateException(
                    "환불 복구 Heartbeat에 실패하여 실행을 중단합니다.",
                    failure
            );
        }
    }
}