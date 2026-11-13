package com.team.sharedcreature.payment.application;

import com.team.sharedcreature._core.exception.BusinessException;
import com.team.sharedcreature._core.exception.ErrorCode;
import com.team.sharedcreature.payment.api.AdminRefundResponse;
import com.team.sharedcreature.payment.domain.PaymentTransaction;
import com.team.sharedcreature.payment.domain.PaymentTransactionStatus;
import com.team.sharedcreature.payment.domain.PaymentTransactionType;
import com.team.sharedcreature.payment.domain.PgApiAttempt;
import com.team.sharedcreature.payment.domain.Purchase;
import com.team.sharedcreature.payment.infrastructure.PaymentTransactionRepository;
import com.team.sharedcreature.payment.infrastructure.PgApiAttemptRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;
import com.team.sharedcreature._core.api.ErrorResponseFactory;
import com.team.sharedcreature.admin.domain.ApiIdempotencyRecord;
import com.team.sharedcreature.payment.application
        .RefundApprovalTransactionService.CommandResponse;
import com.team.sharedcreature.payment.infrastructure.RefundIdempotencyRepository;

import java.util.List;
import java.time.Clock;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * PG 취소 결과를 환불·구매·아이템·거래 이력에 함께 반영합니다.
 * 외부 PG 호출은 수행하지 않습니다.
 */
@RequiredArgsConstructor
@Service
public class RefundResultTransactionService {

    private final RefundLockService refundLockService;
    private final PgApiAttemptRepository pgApiAttemptRepository;
    private final PaymentTransactionRepository transactionRepository;
    private final RefundQueryService refundQueryService;
    private final ObjectMapper objectMapper;
    private final Clock clock;
    private final RefundIdempotencyRepository refundIdempotencyRepository;
    private final ErrorResponseFactory errorResponseFactory;
    private final RefundRecoveryResultResolver recoveryResultResolver;

    /**
     * PG 결과와 최초 HTTP 응답을 하나의 트랜잭션으로 저장합니다.
     * 결과 불명도 예외 대신 반환값으로 전달하여 상태가 롤백되지 않게 합니다.
     *
     * @param command  승인 준비 단계에서 반환한 호출 정보
     * @param received PG 호출 결과
     * @param traceId  최초 요청의 추적 ID
     * @return 저장된 최초 HTTP 상태와 응답
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public CommandResponse apply(
            RefundApprovalTransactionService.CancellationCommand command,
            RefundGateway.CancellationResult received,
            String traceId
    ) {
        Objects.requireNonNull(command, "command");

        // 구매 → 아이템 → 환불 요청을 먼저 잠급니다.
        var locked = refundLockService.lock(command.refundRequestId());
        var purchase = locked.purchase();
        var refund = locked.refund();

        // PG 시도 이력은 환불 요청 다음에 잠급니다.
        var attempt = pgApiAttemptRepository
                .findByIdAndRefundRequestIdForUpdate(
                        command.pgApiAttemptId(),
                        command.refundRequestId()
                )
                .orElseThrow(() -> new BusinessException(
                        ErrorCode.CONCURRENT_MODIFICATION,
                        "취소 준비 이력을 찾을 수 없습니다."
                ));

        validateCommand(command, purchase, attempt);

        // 환불과 PG 시도 이력을 잠근 다음 멱등 기록을 잠급니다.
        var idempotencyRecord = refundIdempotencyRepository
                .findForUpdateByIdAndRefundRequestId(
                        command.idempotencyRecordId(),
                        refund.getId()
                )
                .orElseThrow(() -> new IllegalStateException(
                        "승인 요청에 연결된 멱등 기록이 없습니다."
                ));

        if (!"approveRefund".equals(idempotencyRecord.getOperation())) {
            throw new IllegalStateException(
                    "환불 승인 작업의 멱등 기록이 아닙니다."
            );
        }

        // 이미 응답을 저장했다면 현재 환불 상태로 다시 만들지 않습니다.
        if ("COMPLETED".equals(idempotencyRecord.getStatus())) {
            return objectMapper.readValue(
                    idempotencyRecord.getResponseSnapshot(),
                    CommandResponse.class
            );
        }

        if (!"PROCESSING".equals(idempotencyRecord.getStatus())) {
            throw new IllegalStateException(
                    "처리 중인 멱등 기록이 아닙니다."
            );
        }

        // 동일 호출의 결과와 이미 확정한 환불을 다시 변경하지 않습니다.
        if (attempt.getCompletedAt() != null
                || "COMPLETED".equals(refund.getStatus())
                || "FAILED".equals(refund.getStatus())) {
            // 이미 처리된 결과도 최초 HTTP 응답으로 저장하여 반환합니다.
            return completeResponse(
                    idempotencyRecord,
                    refund.getId(),
                    traceId
            );
        }

        if (!"CANCEL_PENDING".equals(refund.getStatus())
                && !"CANCEL_UNKNOWN".equals(refund.getStatus())) {
            throw new BusinessException(ErrorCode.CONCURRENT_MODIFICATION);
        }

        // 승인과 복구가 동일한 상태 전이·아이템 회수 규칙을 사용합니다.
        applyOutcome(locked, attempt, received);

        return completeResponse(
                idempotencyRecord,
                refund.getId(),
                traceId
        );
    }

    /**
     * 최신 상태 조회 결과를 별도 트랜잭션에서 반영합니다.
     * 다른 조회가 새로 선점했거나 이미 확정된 경우에는 건너뜁니다.
     *
     * @param command 복구 준비 단계에서 반환한 정보
     * @param payment 트랜잭션 밖에서 조회한 최신 결제 정보
     * @return 반영한 결과, 처리권이 유효하지 않으면 빈 Optional
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Optional<RefundGateway.Outcome> applyRecovery(
            RefundRecoveryPreparationService.RecoveryCommand command,
            PaymentSnapshot payment
    ) {
        return applyRecoveryWithinTransaction(command, payment, null);
    }

    /**
     * 현재 트랜잭션에서 복구 결과와 중단된 요청의 멱등 응답을 확정합니다.
     * 현재 수동 요청은 제외하여 실제 요청의 응답 저장 경로를 유지합니다.
     */
    private Optional<RefundGateway.Outcome> applyRecoveryWithinTransaction(
            RefundRecoveryPreparationService.RecoveryCommand command,
            PaymentSnapshot payment,
            Long excludedIdempotencyRecordId
    ) {
        Objects.requireNonNull(command, "command");

        // 구매 → 보유 아이템 → 환불 순서로 다시 잠급니다.
        var locked = refundLockService.lock(command.refundRequestId());
        var purchase = locked.purchase();
        var refund = locked.refund();

        var history = pgApiAttemptRepository
                .findCancellationHistoryForUpdate(refund.getId());

        var cancellation = history.stream()
                .filter(item ->
                        Objects.equals(item.getId(), command.cancellationAttemptId())
                )
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        "복구 대상의 기존 취소 요청 이력이 없습니다."
                ));

        var lookup = history.stream()
                .filter(item ->
                        Objects.equals(item.getId(), command.lookupAttemptId())
                )
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        "복구 준비 단계에서 저장한 조회 이력이 없습니다."
                ));

        // 준비 정보가 현재 구매와 취소·조회 이력에 연결되어 있는지 검사합니다.
        if (!Objects.equals(command.purchaseId(), purchase.getId())
                || !Objects.equals(command.paymentId(), purchase.getOrderNumber())
                || !Objects.equals(command.amountKrw(), purchase.getPriceKrwSnapshot())
                || !Objects.equals(command.currency(), purchase.getCurrency())
                || !"CANCELLATION".equals(cancellation.getOperation())
                || !"CANCELLATION_LOOKUP".equals(lookup.getOperation())
                || !Objects.equals(
                cancellation.getIdempotencyKey(),
                command.pgIdempotencyKey()
        )
                || !Objects.equals(
                lookup.getIdempotencyKey(),
                command.pgIdempotencyKey()
        )) {
            throw new IllegalStateException(
                    "복구 준비 정보와 현재 구매·PG 이력이 일치하지 않습니다."
            );
        }

        // 이미 확정됐거나 같은 조회 결과를 처리했다면 다시 반영하지 않습니다.
        if (!"CANCEL_PENDING".equals(refund.getStatus())
                && !"CANCEL_UNKNOWN".equals(refund.getStatus())) {
            return Optional.empty();
        }

        if (lookup.getCompletedAt() != null) {
            return Optional.empty();
        }

        // 시도 번호 순으로 조회했으므로 마지막 행이 가장 최근 처리권입니다.
        // 더 최근 조회가 생겼다면 이전 조회의 늦은 응답은 반영하지 않습니다.
        if (!Objects.equals(history.getLast().getId(), lookup.getId())) {
            return Optional.empty();
        }

        // 기존 이력 중 마지막으로 확인된 PG 취소 ID를 사용합니다.
        String cancellationId = history.stream()
                .map(PgApiAttempt::getPaymentTransaction)
                .filter(Objects::nonNull)
                .map(PaymentTransaction::getProviderTransactionId)
                .filter(id -> id != null && !id.isBlank())
                .reduce((previous, current) -> current)
                .orElse(null);

        var result = recoveryResultResolver.resolve(
                command,
                payment,
                cancellationId
        );

        // 승인과 같은 규칙으로 상태·회수·거래·조회 이력을 함께 저장합니다.
        applyOutcome(locked, lookup, result);

        // 최신 복구 결과를 반영한 뒤 연결된 미완료 요청의 응답도 확정합니다.
        var processingRecords = refundIdempotencyRepository
                .findProcessingByRefundRequestIdForUpdate(refund.getId());

        for (var processingRecord : processingRecords) {
            // 현재 수동 요청은 실제 요청의 traceId로 별도 확정합니다.
            if (Objects.equals(
                    processingRecord.getId(),
                    excludedIdempotencyRecordId
            )) {
                continue;
            }

            // 응답 저장 전에 중단된 요청에 복구 결과를 최초 응답으로 저장합니다.
            completeResponse(
                    processingRecord,
                    refund.getId(),
                    "refund-recovery-" + command.lookupAttemptId()
            );
        }

        return Optional.of(result.outcome());
    }

    /**
     * 관리자 수동 복구 결과와 최초 HTTP 응답을 함께 저장합니다.
     * 결과 불명도 예외를 던지지 않고 저장 후 반환합니다.
     *
     * @param command 복구 준비 정보
     * @param idempotencyRecordId 수동 복구 요청의 멱등 기록 ID
     * @param payment 트랜잭션 밖에서 조회한 최신 결제 정보
     * @param traceId 현재 요청의 추적 ID
     * @return 저장된 최초 HTTP 상태와 응답
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public CommandResponse applyManualRecovery(
            RefundRecoveryPreparationService.RecoveryCommand command,
            Long idempotencyRecordId,
            PaymentSnapshot payment,
            String traceId
    ) {
        Objects.requireNonNull(command, "command");
        Objects.requireNonNull(idempotencyRecordId, "idempotencyRecordId");

        // 구매 → 보유 아이템 → 환불 순서로 잠급니다.
        var locked = refundLockService.lock(command.refundRequestId());

        // 다른 결과 저장 흐름과 동일하게 PG 이력 다음에 멱등 기록을 잠급니다.
        pgApiAttemptRepository.findCancellationHistoryForUpdate(
                command.refundRequestId()
        );

        var record = refundIdempotencyRepository
                .findForUpdateByIdAndRefundRequestId(
                        idempotencyRecordId,
                        command.refundRequestId()
                )
                .orElseThrow(() -> new IllegalStateException(
                        "수동 복구에 연결된 멱등 기록이 없습니다."
                ));

        if (!"reconcileRefund".equals(record.getOperation())) {
            throw new IllegalStateException(
                    "수동 복구 작업의 멱등 기록이 아닙니다."
            );
        }

        // 이미 응답을 저장했다면 상태 변경 없이 최초 응답을 재사용합니다.
        if ("COMPLETED".equals(record.getStatus())) {
            return objectMapper.readValue(
                    record.getResponseSnapshot(),
                    CommandResponse.class
            );
        }

        if (!"PROCESSING".equals(record.getStatus())) {
            throw new IllegalStateException(
                    "처리 중인 수동 복구 멱등 기록이 아닙니다."
            );
        }

        // 같은 클래스 내부 호출이므로 현재 트랜잭션에서 함께 실행됩니다.
        // 기존 로직이 최신 조회 처리권과 결과 반영 가능 여부를 재검사합니다.
        // 현재 수동 요청의 응답은 아래 completeResponse()에서 저장합니다.
        applyRecoveryWithinTransaction(
                command,
                payment,
                idempotencyRecordId
        );

        // 복구를 건너뛴 경우에도 현재 상태를 기준으로 최초 응답을 저장합니다.
        return completeResponse(
                record,
                locked.refund().getId(),
                traceId
        );
    }

    /**
     * PG 결과를 환불·구매·아이템·거래·호출 이력에 함께 반영합니다.
     * 호출한 public 메서드의 트랜잭션 안에서 실행됩니다.
     */
    private void applyOutcome(
            RefundLockService.LockedRefund locked,
            PgApiAttempt attempt,
            RefundGateway.CancellationResult received
    ) {
        var purchase = locked.purchase();
        var refund = locked.refund();

        var result = normalizeResult(purchase, received);
        var transaction = resolveTransaction(purchase, attempt, result);
        var now = clock.instant();

        PaymentTransactionStatus nextStatus = switch (result.outcome()) {
            case SUCCESS -> PaymentTransactionStatus.SUCCESS;
            case DEFINITIVE_FAILURE -> PaymentTransactionStatus.DEFINITIVE_FAILURE;
            case PENDING -> PaymentTransactionStatus.REQUESTED;
            case UNKNOWN -> PaymentTransactionStatus.UNKNOWN;
        };

        // 연결된 거래가 이미 다른 결과로 확정됐다면 임의로 덮어쓰지 않습니다.
        boolean transactionFinal =
                PaymentTransactionStatus.SUCCESS.name().equals(transaction.getStatus())
                        || PaymentTransactionStatus.DEFINITIVE_FAILURE.name()
                        .equals(transaction.getStatus());

        if (transactionFinal && !nextStatus.name().equals(transaction.getStatus())) {
            throw new IllegalStateException("취소 거래와 PG 결과가 일치하지 않습니다.");
        }

        transaction.applyCancellationResult(
                nextStatus,
                result.providerTransactionId(),
                result.processedAt(),
                result.providerResultCode(),
                now
        );

        switch (result.outcome()) {
            case SUCCESS -> {
                if (locked.item() == null) {
                    throw new IllegalStateException(
                            "환불 완료할 보유 아이템이 없습니다."
                    );
                }

                // 아래 변경 중 하나라도 실패하면 모두 함께 롤백됩니다.
                refund.completeCancellation(result.processedAt(), now);
                purchase.markCancelledAfterRefund(now);
                locked.item().revokeAfterRefund(now);
            }
            case DEFINITIVE_FAILURE -> refund.failCancellation(now);
            case PENDING -> refund.markCancellationPending(now);
            case UNKNOWN -> refund.markCancellationUnknown(now);
        }

        // PG 원문 대신 필요한 내부 결과만 JSON으로 기록합니다.
        String summary = objectMapper.writeValueAsString(Map.of(
                "outcome", result.outcome().name(),
                "refundRequestId", refund.getId(),
                "pgApiAttemptId", attempt.getId()
        ));

        attempt.complete(
                result.outcome().name(),
                result.httpStatus(),
                result.providerResultCode(),
                summary,
                transaction,
                now
        );
    }

    /**
     * 환불 상태에 맞는 최초 HTTP 응답을 생성하고 멱등 기록에 저장합니다.
     * 환불 상태 변경과 응답 저장은 같은 트랜잭션에서 확정됩니다.
     */
    private CommandResponse completeResponse(
            ApiIdempotencyRecord record,
            Long refundRequestId,
            String traceId
    ) {
        AdminRefundResponse data =
                refundQueryService.getAdminRefund(refundRequestId);

        CommandResponse response;

        if ("CANCEL_UNKNOWN".equals(data.status())) {
            // 결과 불명은 상태를 저장한 뒤 HTTP 409로 전달합니다.
            var error = errorResponseFactory.create(
                    ErrorCode.PG_CANCEL_RESULT_UNKNOWN,
                    null,
                    List.of(),
                    traceId
            );

            response = new CommandResponse(409, null, error);
        } else {
            response = new CommandResponse(200, data, null);
        }

        // 이후 재요청에도 최초 HTTP 상태와 응답을 그대로 재사용합니다.
        record.complete(
                response.httpStatus(),
                "REFUND_REQUEST",
                refundRequestId,
                objectMapper.writeValueAsString(response),
                clock.instant()
        );

        return response;
    }

    /**
     * 승인 당시 정보와 현재 잠근 구매·PG 시도 이력의 연결을 검사합니다.
     */
    private void validateCommand(
            RefundApprovalTransactionService.CancellationCommand command,
            Purchase purchase,
            PgApiAttempt attempt
    ) {
        if (!Objects.equals(command.purchaseId(), purchase.getId())
                || !Objects.equals(attempt.getPurchase().getId(), purchase.getId())
                || !Objects.equals(command.paymentId(), purchase.getOrderNumber())
                || !Objects.equals(command.amountKrw(), purchase.getPriceKrwSnapshot())
                || !Objects.equals(command.currency(), purchase.getCurrency())
                || !Objects.equals(command.pgIdempotencyKey(), attempt.getIdempotencyKey())
                || !"CANCELLATION".equals(attempt.getOperation())) {
            throw new BusinessException(
                    ErrorCode.CONCURRENT_MODIFICATION,
                    "취소 준비 정보와 현재 구매·PG 이력이 일치하지 않습니다."
            );
        }
    }

    /**
     * 성공 근거가 부족하거나 응답이 잘못되면 결과 불명으로 처리합니다.
     */
    private RefundGateway.CancellationResult normalizeResult(
            Purchase purchase,
            RefundGateway.CancellationResult result
    ) {
        boolean invalid = result == null
                || result.outcome() == null
                || !purchase.getOrderNumber().equals(result.paymentId());

        if (!invalid) {
            invalid = (result.httpStatus() != null
                    && (result.httpStatus() < 100 || result.httpStatus() > 599))
                    || (result.providerTransactionId() != null
                    && (result.providerTransactionId().isBlank()
                    || result.providerTransactionId().length() > 255))
                    || (result.providerResultCode() != null
                    && result.providerResultCode().length() > 100);
        }

        if (!invalid && result.outcome() == RefundGateway.Outcome.SUCCESS) {
            invalid = !"KRW".equals(purchase.getCurrency())
                    || !Objects.equals(
                    purchase.getPriceKrwSnapshot(),
                    result.cancelledAmountKrw()
            )
                    || result.providerTransactionId() == null
                    || result.processedAt() == null;
        }

        if (invalid) {
            return new RefundGateway.CancellationResult(
                    RefundGateway.Outcome.UNKNOWN,
                    purchase.getOrderNumber(),
                    null, null, null, null, null
            );
        }

        // 대기·결과 불명은 완료 시각을 기록하지 않습니다.
        boolean unresolved = result.outcome() == RefundGateway.Outcome.PENDING
                || result.outcome() == RefundGateway.Outcome.UNKNOWN;

        return new RefundGateway.CancellationResult(
                result.outcome(),
                result.paymentId(),
                result.providerTransactionId(),
                result.cancelledAmountKrw(),
                unresolved ? null : result.processedAt(),
                result.httpStatus(),
                result.providerResultCode()
        );
    }

    /**
     * 해당 시도의 거래를 재사용하고, 없으면 PG 거래 ID로 확인한 뒤 생성합니다.
     */
    private PaymentTransaction resolveTransaction(
            Purchase purchase,
            PgApiAttempt attempt,
            RefundGateway.CancellationResult result
    ) {
        PaymentTransaction transaction = attempt.getPaymentTransaction();

        if (transaction == null && result.providerTransactionId() != null) {
            transaction = transactionRepository
                    .findByProviderAndProviderTransactionId(
                            "PORTONE",
                            result.providerTransactionId()
                    )
                    .orElse(null);
        }

        if (transaction == null) {
            return transactionRepository.save(
                    PaymentTransaction.cancellationRequested(
                            purchase,
                            clock.instant()
                    )
            );
        }

        if (!Objects.equals(purchase.getId(), transaction.getPurchase().getId())
                || !PaymentTransactionType.CANCELLATION.name()
                .equals(transaction.getTransactionType())) {
            throw new IllegalStateException(
                    "동일 구매의 취소 거래만 환불 결과에 연결할 수 있습니다."
            );
        }

        return transaction;
    }
}