package com.team.sharedcreature.payment.infrastructure;

import com.team.sharedcreature.payment.application.PaymentGateway;
import com.team.sharedcreature.payment.application.PaymentSnapshot;
import com.team.sharedcreature.payment.application.RefundLookupGateway;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.client.RestClient;

/**
 * 환불 전용 HTTP 클라이언트로 최신 결제와 취소 내역을 조회합니다.
 * 기존 PortOne 조회·응답 변환 코드를 재사용합니다.
 */
@Component
public class PortOneRefundLookupGateway implements RefundLookupGateway {

    private final PaymentGateway delegate;

    /**
     * 기존 결제 API와 구분된 환불 전용 클라이언트를 사용합니다.
     *
     * @param properties 기존 PortOne 설정
     * @param restClient 연결·응답 타임아웃이 설정된 환불 전용 클라이언트
     */
    public PortOneRefundLookupGateway(
            PortOneProperties properties,
            @Qualifier("refundRestClient") RestClient restClient
    ) {
        // 별도의 Spring Bean으로 등록하지 않고 내부 조회 객체로 사용합니다.
        this.delegate = new PortOnePaymentGateway(properties, restClient);
    }

    /**
     * DB 트랜잭션 밖에서 최신 결제 상태와 기존 취소 내역을 조회합니다.
     * 조회 실패 시 기존 PaymentGatewayException을 그대로 전달합니다.
     */
    @Override
    public PaymentSnapshot getPayment(String paymentId) {
        // 외부 HTTP 응답을 기다리는 동안 DB 트랜잭션을 유지하지 않습니다.
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException(
                    "환불 상태 조회는 DB 트랜잭션 밖에서 호출해야 합니다."
            );
        }

        if (paymentId == null || paymentId.isBlank()) {
            throw new IllegalArgumentException(
                    "조회할 결제 ID가 필요합니다."
            );
        }

        // 최신 조회만 수행하며 취소 요청을 재전송하지 않습니다.
        return delegate.getPayment(paymentId);
    }
}