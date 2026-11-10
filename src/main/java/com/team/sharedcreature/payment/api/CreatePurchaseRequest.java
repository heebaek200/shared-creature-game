package com.team.sharedcreature.payment.api;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

/** 서버 가격으로 주문을 생성할 상점 상품 입력입니다. */
public record CreatePurchaseRequest(
        @NotNull @Min(1) Long shopItemId
) {
}
