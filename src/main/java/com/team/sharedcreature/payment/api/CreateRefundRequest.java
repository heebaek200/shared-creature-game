package com.team.sharedcreature.payment.api;

import com.fasterxml.jackson.annotation.JsonSetter;
import com.team.sharedcreature._core.exception.BusinessException;
import com.team.sharedcreature._core.exception.ErrorCode;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import tools.jackson.databind.JsonNode;

/**
 * 미사용 아이템의 환불 요청 사유를 받습니다.
 * 원본 JSON 타입을 확인하고 trim한 사유를 검증합니다.
 */
public class CreateRefundRequest {

    @NotBlank(message = "환불 요청 사유는 필수입니다.")
    @Size(max = 500, message = "환불 요청 사유는 500자 이하여야 합니다.")
    private String reason;

    /** 현재 요청에서 reason 키가 이미 전달됐는지 기록합니다. */
    private boolean reasonReceived;

    /**
     * JSON 문자열인지 확인하고 앞뒤 공백을 제거합니다.
     *
     * @param value 요청에 전달된 원본 JSON 값
     * @throws BusinessException 문자열 타입이 아닌 경우
     */
    @JsonSetter("reason")
    public void setReason(JsonNode value) {

        // 같은 JSON 키가 다시 전달되면 기존 값을 덮어쓰지 않고 거절합니다.
        if (reasonReceived) {
            throw new BusinessException(
                    ErrorCode.INVALID_REQUEST,
                    "reason은 중복해서 입력할 수 없습니다."
            );
        }

        // 첫 값이 null이어도 키를 전달받았다는 사실은 기록합니다.
        this.reasonReceived = true;

        // 누락과 null은 @NotBlank 검증에서 거절합니다.
        if (value == null || value.isNull()) {
            this.reason = null;
            return;
        }

        // 숫자·boolean 등을 사유 문자열로 자동 변환하지 않습니다.
        if (!value.isTextual()) {
            throw new BusinessException(
                    ErrorCode.INVALID_FIELD_VALUE,
                    "환불 요청 사유는 문자열로 입력해 주세요."
            );
        }

        this.reason = value.textValue().trim();
    }

    /**
     * 앞뒤 공백이 제거된 환불 요청 사유를 반환합니다.
     *
     * @return 정규화된 요청 사유
     */
    public String reason() {
        return reason;
    }
}