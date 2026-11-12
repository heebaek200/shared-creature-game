package com.team.sharedcreature.avatar.api;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.team.sharedcreature.avatar.domain.OwnedItem;
import com.team.sharedcreature.avatar.domain.ShopItem;

/**
 * 사용자의 현재 아바타 장착 정보를 전달합니다.
 *
 * @param userId 사용자 ID
 * @param equipments 네 슬롯의 장착 정보
 */
public record AvatarResponse(
        Long userId,
        Equipments equipments
) {

    /**
     * 아바타의 네 슬롯을 전달합니다.
     *
     * @param HEAD 머리 슬롯, 미장착이면 null
     * @param FACE 얼굴 슬롯, 미장착이면 null
     * @param BODY 몸통 슬롯, 미장착이면 null
     * @param ACCESSORY 장신구 슬롯, 미장착이면 null
     */
    // Equipments는 네 슬롯을 항상 응답에 포함하기 위해 사용.
    // 미장착 슬롯도 키를 유지하고 JSON null로 반환해야 합니다.
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record Equipments(
            Equipment HEAD,
            Equipment FACE,
            Equipment BODY,
            Equipment ACCESSORY
    ) {
    }

    /**
     * 한 슬롯에 장착된 아이템 정보를 전달합니다.
     *
     * @param ownedItemId 보유 아이템 ID
     * @param shopItemId 상점 상품 ID
     * @param name 상품 이름
     * @param assetKey 이미지·리소스 키
     */
    public record Equipment(
            Long ownedItemId,
            Long shopItemId,
            String name,
            String assetKey
    ) {

        /**
         * 장착된 보유 아이템을 슬롯 응답으로 변환합니다.
         *
         * @param ownedItem 장착된 보유 아이템
         * @return 슬롯에 표시할 아이템 정보
         */
        public static Equipment from(OwnedItem ownedItem) {
            ShopItem shopItem = ownedItem.getShopItem();

            return new Equipment(
                    ownedItem.getId(),
                    shopItem.getId(),
                    shopItem.getName(),
                    shopItem.getAssetKey()
            );
        }
    }
}
