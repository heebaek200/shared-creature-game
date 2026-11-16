package com.team.sharedcreature.payment.application;

/**
 * 환불 복구에 필요한 PG 최신 결제 상태를 조회합니다.
 * 취소 요청을 재전송하지 않고 결제와 기존 취소 내역만 확인합니다.
 */
public interface RefundLookupGateway {

    /**
     * PG에서 최신 결제 상태와 취소 내역을 조회합니다.
     * DB 트랜잭션 밖에서 호출해야 합니다.
     *
     * @param paymentId 조회할 PortOne 결제 ID
     * @return 최신 결제 상태와 취소 내역
     * @throws PaymentGatewayException 통신 실패 또는 응답 검증 실패
     */
    PaymentSnapshot getPayment(String paymentId);
}
