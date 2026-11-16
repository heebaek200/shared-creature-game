package com.team.sharedcreature.payment.application;

import java.time.Instant;

/**
 * 환불을 위한 PG 전체 취소 호출 경계입니다.
 * 외부 API의 응답 형식은 구현체에서 내부 결과로 변환합니다.
 */
public interface RefundGateway {

    /**
     * 저장된 멱등 키를 사용하여 결제 전체 취소를 요청합니다.
     * DB 트랜잭션 밖에서 호출해야 합니다.
     *
     * @param paymentId PG 결제 ID
     * @param reason 관리자 승인 사유
     * @param idempotencyKey 환불 요청별로 저장한 PG 멱등 키
     * @return 성공·실패·대기·결과 불명을 구분한 취소 결과
     */
    CancellationResult cancelFully(
            String paymentId,
            String reason,
            String idempotencyKey
    );

    /**
     * 외부 PG 취소 결과를 판단하는 내부 분류입니다.
     */
    enum Outcome {
        SUCCESS,
        DEFINITIVE_FAILURE,
        PENDING,
        UNKNOWN
    }

    /**
     * 전체 취소 응답을 내부 업무에서 사용할 값으로 표현합니다.
     * UNKNOWN 등 미확정 결과에서는 거래 정보가 null일 수 있습니다.
     *
     * @param outcome 확인된 취소 결과
     * @param paymentId 결과가 연결된 결제 ID
     * @param providerTransactionId PG 취소 거래 ID, 미확정이면 null
     * @param cancelledAmountKrw 취소 금액, 확인할 수 없으면 null
     * @param processedAt PG 취소 완료 시각, 미완료이면 null
     * @param httpStatus PG HTTP 응답 상태, 응답이 없으면 null
     * @param providerResultCode PG 결과 코드, 제공되지 않으면 null
     */
    record CancellationResult(
            Outcome outcome,
            String paymentId,
            String providerTransactionId,
            Long cancelledAmountKrw,
            Instant processedAt,
            Integer httpStatus,
            String providerResultCode
    ) {
    }
}