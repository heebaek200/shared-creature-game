package com.team.sharedcreature.payment.api;

import com.fasterxml.jackson.annotation.JsonSetter;
import com.team.sharedcreature._core.exception.BusinessException;
import com.team.sharedcreature._core.exception.ErrorCode;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import tools.jackson.databind.JsonNode;

/**
 * 관리자의 환불 승인·거절 사유를 받습니다.
 * 원본 JSON 타입을 확인하고 trim한 사유를 검증합니다.
 */
public class RefundDecisionRequest {

    @NotBlank(message = "관리자 판단 사유는 필수입니다.")
    @Size(max = 500, message = "관리자 판단 사유는 500자 이하여야 합니다.")
    private String decisionReason;

    /** 현재 요청에서 decisionReason 키가 이미 전달됐는지 기록합니다. */
    private boolean decisionReasonReceived;

    /**
     * JSON 문자열인지 확인하고 앞뒤 공백을 제거합니다.
     *
     * @param value 관리자 판단 사유의 원본 JSON 값
     * @throws BusinessException 문자열 타입이 아닌 경우
     */
    @JsonSetter("decisionReason")
    public void setDecisionReason(JsonNode value) {

        // 같은 키를 다시 전달하면 기존 관리자 판단 사유를 덮어쓰지 않습니다.
        if (decisionReasonReceived) {
            throw new BusinessException(
                    ErrorCode.INVALID_REQUEST,
                    "decisionReason은 중복해서 입력할 수 없습니다."
            );
        }

        // 첫 값이 null이어도 키를 전달받은 사실을 기록합니다.
        this.decisionReasonReceived = true;

        // 누락과 null은 @NotBlank 검증에서 거절합니다.
        if (value == null || value.isNull()) {
            this.decisionReason = null;
            return;
        }

        // 숫자·boolean 등을 사유 문자열로 자동 변환하지 않습니다.
        if (!value.isTextual()) {
            throw new BusinessException(
                    ErrorCode.INVALID_FIELD_VALUE,
                    "관리자 판단 사유는 문자열로 입력해 주세요."
            );
        }

        this.decisionReason = value.textValue().trim();
    }

    /**
     * 앞뒤 공백이 제거된 관리자 판단 사유를 반환합니다.
     *
     * @return 정규화된 관리자 판단 사유
     */
    public String decisionReason() {
        return decisionReason;
    }
}