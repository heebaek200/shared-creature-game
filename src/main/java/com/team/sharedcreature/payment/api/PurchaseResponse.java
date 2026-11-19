package com.team.sharedcreature.payment.api;

import com.team.sharedcreature.avatar.domain.OwnedItemStatus;
import com.team.sharedcreature.payment.domain.PaymentTransactionStatus;
import com.team.sharedcreature.payment.domain.PaymentTransactionType;
import com.team.sharedcreature.payment.domain.PurchaseStatus;
import com.team.sharedcreature.payment.domain.RefundStatus;

import java.time.Instant;
import java.util.List;

/** 구매·지급·PG 거래·환불 상태를 화면 단위로 반환합니다. */
public record PurchaseResponse(
        Long purchaseId,
        String orderNumber,
        String itemName,
        Long priceKrw,
        String currency,
        PurchaseStatus status,
        Instant createdAt,
        OwnedItemStatus ownedItemStatus,
        RefundStatus latestRefundStatus,
        Long shopItemId,
        List<Transaction> transactions,
        List<Refund> refundRequests
) {
    /** 공급자 승인·취소 거래 이력입니다. */
    public record Transaction(
            Long transactionId,
            String provider,
            String providerTransactionId,
            PaymentTransactionType transactionType,
            Long amountKrw,
            PaymentTransactionStatus status,
            Instant processedAt
    ) {
    }

    /** 구매에 연결된 환불 요청 이력입니다. */
    public record Refund(
            Long refundRequestId,
            Long purchaseId,
            Long requestedByUserId,
            String requestReason,
            String decisionReason,
            RefundStatus status,
            Instant requestedAt,
            Instant reviewedAt,
            Instant completedAt
    ) {
    }
}
