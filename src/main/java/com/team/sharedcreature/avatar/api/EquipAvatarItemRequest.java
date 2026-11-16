package com.team.sharedcreature.avatar.api;

import com.fasterxml.jackson.annotation.JsonSetter;
import com.fasterxml.jackson.annotation.Nulls;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import com.team.sharedcreature._core.exception.BusinessException;
import com.team.sharedcreature._core.exception.ErrorCode;
import tools.jackson.databind.JsonNode;

/**
 * 장착할 보유 아이템과 최초 사용 동의 여부를 전달합니다.
 */
public class EquipAvatarItemRequest {

        @NotNull(message = "보유 아이템 ID는 필수입니다.")
        @Min(value = 1, message = "보유 아이템 ID는 1 이상이어야 합니다.")
        private Long ownedItemId;

        // 요청에서 생략하면 boolean의 기본값인 false를 유지합니다.
        private boolean confirmFirstUse;

        /**
         * 원본 JSON 값의 타입을 확인한 뒤 보유 아이템 ID를 설정합니다.
         *
         * @param value 요청에 전달된 원본 JSON 값
         * @throws BusinessException 정수 타입이 아니거나 int64 범위를 벗어난 경우
         */
        @JsonSetter("ownedItemId")
        public void setOwnedItemId(JsonNode value) {
                // 필수값 누락과 null은 기존 @NotNull 검증에서 처리합니다.
                if (value == null || value.isNull()) {
                        this.ownedItemId = null;
                        return;
                }

                if (!value.isIntegralNumber() || !value.canConvertToLong()) {
                        throw new BusinessException(
                                ErrorCode.INVALID_FIELD_VALUE,
                                "ownedItemId는 정수로 입력해 주세요."
                        );
                }

                this.ownedItemId = value.longValue();
        }

        /**
         * 원본 JSON 값이 boolean인지 확인하고 최초 사용 동의를 설정합니다.
         *
         * @param value 최초 사용 동의의 원본 JSON 값
         * @throws BusinessException boolean 타입이 아닌 경우
         */
        @JsonSetter(value = "confirmFirstUse", nulls = Nulls.FAIL)
        public void setConfirmFirstUse(JsonNode value) {
                // 문자열이나 숫자를 boolean으로 자동 변환하지 않습니다.
                if (!value.isBoolean()) {
                        throw new BusinessException(
                                ErrorCode.INVALID_FIELD_VALUE,
                                "confirmFirstUse는 true 또는 false로 입력해 주세요."
                        );
                }

                this.confirmFirstUse = value.booleanValue();
        }

        /**
         * 장착할 보유 아이템 ID를 반환합니다.
         *
         * @return 보유 아이템 ID
         */
        public Long ownedItemId() {
                return ownedItemId;
        }

        /**
         * 최초 사용 동의 여부를 반환합니다.
         *
         * @return 최초 사용 동의 여부
         */
        public boolean confirmFirstUse() {
                return confirmFirstUse;
        }
}
