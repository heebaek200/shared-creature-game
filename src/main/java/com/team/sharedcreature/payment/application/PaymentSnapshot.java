package com.team.sharedcreature.payment.application;

import java.time.Instant;
import java.util.List;

/** PortOne DTO가 도메인으로 전파되지 않도록 정규화한 최신 결제 Snapshot입니다. */
public record PaymentSnapshot(
        String paymentId,
        String storeId,
        String transactionId,
        Status status,
        long totalAmount,
        String currency,
        String payMethod,
        String easyPayProvider,
        String channelType,
        Instant paidAt,
        List<CancellationSnapshot> cancellations
) {
    /** 결제 정합화에서 사용하는 공급자 상태입니다. */
    public enum Status {
        READY,
        PENDING,
        PAID,
        FAILED,
        CANCELLED,
        PARTIAL_CANCELLED,
        UNKNOWN
    }

    /** #22 환불 복구가 재사용할 공급자 취소 Snapshot입니다. */
    public record CancellationSnapshot(
            String cancellationId,
            String status,
            long cancelledAmount,
            Instant requestedAt,
            Instant cancelledAt
    ) {
    }
}
