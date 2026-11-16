package com.team.sharedcreature.payment.api;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.team.sharedcreature.avatar.domain.OwnedItem;
import com.team.sharedcreature.payment.domain.PaymentTransaction;
import com.team.sharedcreature.payment.domain.RefundRequest;

import java.time.Instant;

/**
 * 관리자 환불 상세와 승인·거절·복구 결과를 반환합니다.
 * 미확정 정보도 응답에서 키를 유지합니다.
 */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record AdminRefundResponse(
        Long refundRequestId,
        Long purchaseId,
        Long requestedByUserId,
        String requestReason,
        String decisionReason,
        String status,
        Instant requestedAt,
        Instant reviewedAt,
        Instant completedAt,
        Long reviewedByUserId,
        PurchaseInfo purchase,
        OwnedItemInfo ownedItem,
        CancellationInfo latestCancellation
) {

    /**
     * 환불 요청과 연결된 구매·아이템·최근 취소 거래를 응답으로 변환합니다.
     *
     * @param refund 환불 요청
     * @param item 보유 아이템, 미지급이면 null
     * @param cancellation 최근 취소 거래, 취소 시도 전이면 null
     * @return 관리자 환불 상세 응답
     */
    public static AdminRefundResponse from(
            RefundRequest refund,
            OwnedItem item,
            PaymentTransaction cancellation
    ) {
        var purchase = refund.getPurchase();

        return new AdminRefundResponse(
                refund.getId(),
                purchase.getId(),
                refund.getRequestedByUser().getId(),
                refund.getRequestReason(),
                refund.getDecisionReason(),
                // 환불 상태 Enum을 기존 API 문자열로 변환합니다.
                refund.getStatus().name(),
                refund.getRequestedAt(),
                refund.getReviewedAt(),
                refund.getCompletedAt(),
                refund.getReviewedByUser() == null
                        ? null
                        : refund.getReviewedByUser().getId(),
                new PurchaseInfo(
                        purchase.getOrderNumber(),
                        purchase.getItemNameSnapshot(),
                        purchase.getPriceKrwSnapshot(),
                        purchase.getStatus()
                ),
                // 미지급 아이템은 빈 객체 대신 null로 반환합니다.
                item == null ? null : new OwnedItemInfo(
                        item.getId(),
                        // 보유 상태 Enum을 기존 API 문자열로 변환합니다.
                        item.getStatus().name(),
                        item.getUsedAt()
                ),
                // 취소 거래가 없다면 latestCancellation 키를 null로 유지합니다.
                cancellation == null ? null : new CancellationInfo(
                        cancellation.getProviderTransactionId(),
                        cancellation.getStatus(),
                        cancellation.getProcessedAt()
                )
        );
    }

    /**
     * 구매 당시 상품 정보와 현재 구매 상태입니다.
     */
    public record PurchaseInfo(
            String orderNumber,
            String itemName,
            Long priceKrw,
            String status
    ) {
    }

    /**
     * 보유 아이템의 상태와 최초 사용 시각입니다.
     */
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record OwnedItemInfo(
            Long ownedItemId,
            String status,
            Instant usedAt
    ) {
    }

    /**
     * 가장 최근 PG 취소 거래의 상태입니다.
     */
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record CancellationInfo(
            String providerTransactionId,
            String status,
            Instant processedAt
    ) {
    }
}