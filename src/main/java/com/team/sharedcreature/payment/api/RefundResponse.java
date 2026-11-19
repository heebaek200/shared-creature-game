package com.team.sharedcreature.payment.api;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.team.sharedcreature.payment.domain.RefundRequest;
import com.team.sharedcreature.payment.domain.RefundStatus;

import java.time.Instant;

/**
 * 환불 요청 생성과 사용자·관리자 목록의 응답을 정의합니다.
 * 미심사·미완료 필드도 null 키를 유지합니다.
 */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record RefundResponse(
        Long refundRequestId,
        Long purchaseId,
        Long requestedByUserId,
        String requestReason,
        String decisionReason,
        RefundStatus status,
        Instant requestedAt,
        Instant withdrawnAt,
        Instant reviewedAt,
        Instant completedAt
) {

    /**
     * 저장된 환불 요청을 API 응답으로 변환합니다.
     *
     * @param refund 환불 요청 엔티티
     * @return 환불 요청 기본 응답
     */
    public static RefundResponse from(RefundRequest refund) {
        // 구매와 신청 사용자에서는 식별자만 가져옵니다.
        return new RefundResponse(
                refund.getId(),
                refund.getPurchase().getId(),
                refund.getRequestedByUser().getId(),
                refund.getRequestReason(),
                refund.getDecisionReason(),
                // 환불 상태 enum은 JSON에 이름으로 전달합니다.
                refund.getStatus(),
                refund.getRequestedAt(),
                refund.getWithdrawnAt(),
                refund.getReviewedAt(),
                refund.getCompletedAt()
        );
    }
}
