package com.team.sharedcreature.avatar.api;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.team.sharedcreature.avatar.domain.OwnedItem;
import com.team.sharedcreature.avatar.domain.ShopItem;

import java.time.Instant;

/**
 * 내 보유 아이템 목록의 아이템 정보를 전달합니다.
 *
 * @param ownedItemId 보유 아이템 ID
 * @param purchaseId 구매 ID
 * @param shopItemId 상점 상품 ID
 * @param name 상품 이름
 * @param slot 장착 슬롯
 * @param assetKey 이미지, 리소스 키
 * @param status 보유 아이템 상태
 * @param usedAt 최초 사용 시각, 미사용이면 null
 * @param revokedAt 회수 시각, 회수 전이면 null
 * @param equipped 현재 장착 여부
 */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record OwnedItemResponse(
        Long ownedItemId,
        Long purchaseId,
        Long shopItemId,
        String name,
        String slot,
        String assetKey,
        String status,
        Instant usedAt,
        Instant revokedAt,
        boolean equipped
) {

    /**
     * 보유 아이템과 현재 장착 여부를 응답 DTO로 변환합니다.
     *
     * @param ownedItem 조회한 보유 아이템
     * @param equipped 현재 아바타에 장착되어 있는지 여부
     * @return 보유 아이템 응답
     */
    public static OwnedItemResponse from(
            OwnedItem ownedItem,
            boolean equipped
    ) {
        ShopItem shopItem = ownedItem.getShopItem();

        return new OwnedItemResponse(
                ownedItem.getId(),
                ownedItem.getPurchase().getId(),
                shopItem.getId(),
                shopItem.getName(),
                shopItem.getSlotCode(),
                shopItem.getAssetKey(),
                ownedItem.getStatus(),
                ownedItem.getUsedAt(),
                ownedItem.getRevokedAt(),
                equipped
        );
    }
}