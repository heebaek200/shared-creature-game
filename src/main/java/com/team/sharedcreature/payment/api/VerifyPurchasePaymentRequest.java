package com.team.sharedcreature.payment.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

/** 브라우저 결제 완료 후 서버 검증을 요청하는 최소 입력입니다. */
public record VerifyPurchasePaymentRequest(
        @NotBlank
        @Pattern(regexp = "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[1-5][0-9a-fA-F]{3}-[89abAB][0-9a-fA-F]{3}-[0-9a-fA-F]{12}$")
        String paymentId
) {
}
