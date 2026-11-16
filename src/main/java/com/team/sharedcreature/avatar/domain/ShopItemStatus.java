package com.team.sharedcreature.avatar.domain;

/**
 * 상점 상품의 판매 상태입니다.
 *
 * Enum 이름은 기존 DB 저장값과 API 응답 문자열을 그대로 유지합니다.
 */
public enum ShopItemStatus {

    // 판매 중이며 상점 상품 목록에 노출되는 상태입니다.
    ACTIVE,

    // 판매가 중단되어 상점 상품 목록에서 제외되는 상태입니다.
    INACTIVE
}