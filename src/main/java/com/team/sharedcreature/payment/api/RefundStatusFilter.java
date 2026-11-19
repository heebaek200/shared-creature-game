package com.team.sharedcreature.payment.api;

import com.team.sharedcreature._core.exception.BusinessException;
import com.team.sharedcreature._core.exception.ErrorCode;
import com.team.sharedcreature.payment.domain.RefundStatus;

final class RefundStatusFilter {

    private RefundStatusFilter() {
    }

    /**
     * 선택적인 환불 상태 필터를 도메인 타입으로 변환합니다.
     *
     * @param status 요청 환불 상태, 미지정이면 null
     * @return 환불 상태, 미지정이면 null
     * @throws BusinessException 허용되지 않은 환불 상태인 경우
     */
    static RefundStatus parse(String status) {
        if (status == null) {
            return null;
        }

        try {
            // 대소문자와 공백을 보정하지 않고 기존 허용값을 유지합니다.
            return RefundStatus.valueOf(status);
        } catch (IllegalArgumentException exception) {
            throw new BusinessException(
                    ErrorCode.INVALID_FIELD_VALUE,
                    "지원하지 않는 환불 상태입니다."
            );
        }
    }
}
