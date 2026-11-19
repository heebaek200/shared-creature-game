package com.team.sharedcreature.payment.application;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Objects;

/**
 * 최신 결제 조회 결과를 환불 복구 결과로 변환합니다.
 * 취소 거래를 식별하거나 결과를 확정할 수 없으면 UNKNOWN을 반환합니다.
 */
@Component
public class RefundRecoveryResultResolver {

    private final String storeId;

    /** 기존 PortOne 상점 설정을 읽습니다. */
    public RefundRecoveryResultResolver(
            @Value("${portone.store-id}") String storeId
    ) {
        this.storeId = storeId;
    }

    /**
     * 구매 정보와 PG 취소 내역을 비교하여 결과를 판정합니다.
     *
     * @param command 복구 준비 정보
     * @param payment 최신 결제 조회 결과
     * @param cancellationId DB에 저장된 PG 취소 ID, 미확정이면 null
     * @return 성공·확정 실패·대기·결과 불명
     */
    public RefundGateway.CancellationResult resolve(
            RefundRecoveryPreparationService.RecoveryCommand command,
            PaymentSnapshot payment,
            String cancellationId
    ) {
        Objects.requireNonNull(command, "command");

        // 다른 결제·상점 또는 금액·통화가 다른 응답은 확정에 사용하지 않습니다.
        if (payment == null
                || storeId == null
                || storeId.isBlank()
                || !Objects.equals(storeId, payment.storeId())
                || !Objects.equals(command.paymentId(), payment.paymentId())
                || command.amountKrw() == null
                || command.amountKrw() < 1
                || command.amountKrw().longValue() != payment.totalAmount()
                || !"KRW".equals(command.currency())
                || !Objects.equals(command.currency(), payment.currency())
                || payment.cancellations() == null) {
            return unknown(command);
        }

        List<PaymentSnapshot.CancellationSnapshot> cancellations = payment.cancellations();

        boolean knownId = cancellationId != null
                && !cancellationId.isBlank();

        PaymentSnapshot.CancellationSnapshot target;

        if (knownId) {
            // 이미 취소 ID를 받았다면 정확히 같은 거래만 선택합니다.
            List<PaymentSnapshot.CancellationSnapshot> matches = cancellations.stream()
                    .filter(Objects::nonNull)
                    .filter(item ->
                            cancellationId.equals(item.cancellationId())
                    )
                    .toList();

            if (matches.size() != 1) {
                return unknown(command);
            }

            target = matches.getFirst();
        } else {
            // Timeout으로 ID를 못 받은 경우에는 단일 전체 취소 성공만 인정합니다.
            // 과거 실패 내역을 이번 취소의 실패로 추정하지 않습니다.
            if (payment.status() != PaymentSnapshot.Status.CANCELLED
                    || cancellations.size() != 1) {
                return unknown(command);
            }

            target = cancellations.getFirst();
        }

        if (target == null
                || target.cancellationId() == null
                || target.cancellationId().isBlank()
                || target.cancellationId().length() > 255) {
            return unknown(command);
        }

        RefundGateway.Outcome outcome;

        if ("SUCCEEDED".equals(target.status())
                && payment.status() == PaymentSnapshot.Status.CANCELLED
                && target.cancelledAmount() == command.amountKrw().longValue()
                && target.cancelledAt() != null) {
            outcome = RefundGateway.Outcome.SUCCESS;
        } else if (knownId
                && "FAILED".equals(target.status())
                && payment.status() == PaymentSnapshot.Status.PAID) {
            outcome = RefundGateway.Outcome.DEFINITIVE_FAILURE;
        } else if (knownId
                && "REQUESTED".equals(target.status())
                && payment.status() == PaymentSnapshot.Status.PAID) {
            outcome = RefundGateway.Outcome.PENDING;
        } else {
            return unknown(command);
        }

        return new RefundGateway.CancellationResult(
                outcome,
                command.paymentId(),
                target.cancellationId(),
                target.cancelledAmount(),
                outcome == RefundGateway.Outcome.SUCCESS
                        ? target.cancelledAt()
                        : null,
                200,
                target.status()
        );
    }

    /** 확정 근거가 부족하면 취소 성공·실패를 추정하지 않습니다. */
    private RefundGateway.CancellationResult unknown(
            RefundRecoveryPreparationService.RecoveryCommand command
    ) {
        return new RefundGateway.CancellationResult(
                RefundGateway.Outcome.UNKNOWN,
                command.paymentId(),
                null,
                null,
                null,
                null,
                null
        );
    }
}