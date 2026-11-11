package com.team.sharedcreature.avatar.api;

import com.fasterxml.jackson.annotation.JsonSetter;
import com.fasterxml.jackson.annotation.Nulls;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

/**
 * 아바타 슬롯에 장착할 아이템과 최초 사용 동의 여부를 전달합니다.
 *
 * @param ownedItemId 장착할 보유 아이템 ID
 * @param confirmFirstUse 최초 사용과 환불 불가 전환에 대한 동의 여부
 */
public record EquipAvatarItemRequest(

        @NotNull(message = "보유 아이템 ID는 필수입니다.")
        @Min(value = 1, message = "보유 아이템 ID는 1 이상이어야 합니다.")
        Long ownedItemId,

        // 생략하면 false를 사용하되 명시적인 null 입력은 거부합니다.
        @JsonSetter(nulls = Nulls.FAIL)
        boolean confirmFirstUse
) {
}
