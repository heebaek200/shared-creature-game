package com.team.sharedcreature.payment.api;

import java.time.Instant;

/** 구매 목록 한 행의 결제·지급·최근 환불 상태입니다. */
public record PurchaseSummaryResponse(
        Long purchaseId,
        String orderNumber,
        String itemName,
        Long priceKrw,
        String currency,
        String status,
        Instant createdAt,
        String ownedItemStatus,
        String latestRefundStatus
) {
}
