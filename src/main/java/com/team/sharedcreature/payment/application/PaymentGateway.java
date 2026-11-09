package com.team.sharedcreature.payment.application;

/** 외부 PG의 최신 결제 상태를 애플리케이션 모델로 조회하는 Port입니다. */
public interface PaymentGateway {

    /** 고객사 결제 ID로 최신 공급자 결제를 조회합니다. */
    PaymentSnapshot getPayment(String paymentId);
}
