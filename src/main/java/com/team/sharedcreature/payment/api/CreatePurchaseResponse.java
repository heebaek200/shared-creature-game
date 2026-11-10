package com.team.sharedcreature.payment.api;

import java.time.Instant;

/** 결제창 호출에 필요한 공개 설정과 서버 주문 Snapshot입니다. */
public record CreatePurchaseResponse(
        Long purchaseId,
        String orderNumber,
        Long shopItemId,
        String itemName,
        Long totalAmount,
        String currency,
        Payment payment,
        Instant createdAt
) {
    /** PortOne Browser SDK에 전달할 공개 결제 설정입니다. */
    public record Payment(
            String storeId,
            String channelKey,
            String paymentId,
            String orderName,
            Long totalAmount,
            String currency,
            String payMethod,
            EasyPay easyPay,
            WindowType windowType
    ) {
    }

    /** 단일 카카오페이 채널 설정입니다. */
    public record EasyPay(String easyPayProvider) {
    }

    /** PC·모바일 결제창 표시 방식입니다. */
    public record WindowType(String pc, String mobile) {
    }
}
