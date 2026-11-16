package com.team.sharedcreature.avatar.api;

import com.team.sharedcreature.avatar.domain.ShopItem;

/**
 * 상점 상품 목록과 상세 조회의 상품 정보를 전달합니다.
 *
 * @param shopItemId 상점 상품 ID
 * @param code 상품 코드
 * @param name 상품 이름
 * @param description 상품 설명
 * @param priceKrw 원 단위 가격
 * @param slot 장착 슬롯
 * @param assetKey 이미지·리소스 키
 * @param status 상품 상태
 */
// record : 응답 데이터를 담는 클래스를 간결하게 작성하는 문법입니다.
//          선언한 필드의 생성자와 접근 메서드가 자동으로 만들어짐
public record ShopItemResponse(
        Long shopItemId,
        String code,
        String name,
        String description,
        Long priceKrw,
        String slot,
        String assetKey,
        String status
) {

    /**
     * 상품 엔티티를 API 응답 DTO로 변환합니다.
     *
     * @param shopItem 조회한 상품 엔티티
     * @return API 계약에 맞는 상품 정보
     */
    public static ShopItemResponse from(ShopItem shopItem) {
        return new ShopItemResponse(
                shopItem.getId(),
                shopItem.getCode(),
                shopItem.getName(),
                shopItem.getDescription(),
                shopItem.getPriceKrw(),
                shopItem.getSlotCode(),
                shopItem.getAssetKey(),
                shopItem.getStatus()
        );
    }
}
