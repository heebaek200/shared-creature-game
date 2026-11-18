package com.team.sharedcreature.payment.api;

import com.team.sharedcreature.avatar.domain.OwnedItemStatus;
import com.team.sharedcreature.payment.domain.PurchaseStatus;
import com.team.sharedcreature.payment.domain.RefundStatus;

import java.time.Instant;

/** 구매 목록 한 행의 결제·지급·최근 환불 상태입니다. */
public record PurchaseSummaryResponse(
        Long purchaseId,
        String orderNumber,
        String itemName,
        Long priceKrw,
        String currency,
        PurchaseStatus status,
        Instant createdAt,
        OwnedItemStatus ownedItemStatus,
        RefundStatus latestRefundStatus
) {
}
