package com.team.sharedcreature.avatar.api;

import com.fasterxml.jackson.annotation.JsonSetter;
import com.fasterxml.jackson.annotation.Nulls;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

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
         * 요청의 보유 아이템 ID를 설정합니다.
         *
         * @param ownedItemId 장착할 보유 아이템 ID
         */
        @JsonSetter("ownedItemId")
        public void setOwnedItemId(Long ownedItemId) {
                this.ownedItemId = ownedItemId;
        }

        /**
         * 전달된 최초 사용 동의 여부를 설정합니다.
         *
         * @param confirmFirstUse 최초 사용 동의 여부, 명시적인 null은 거부
         */
        @JsonSetter(value = "confirmFirstUse", nulls = Nulls.FAIL)
        public void setConfirmFirstUse(boolean confirmFirstUse) {
                this.confirmFirstUse = confirmFirstUse;
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
