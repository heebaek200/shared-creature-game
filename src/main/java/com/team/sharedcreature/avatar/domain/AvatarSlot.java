package com.team.sharedcreature.avatar.domain;

/**
 * 상품과 아바타 장착에 사용하는 슬롯입니다.
 *
 * Enum 이름은 기존 DB 저장값과 API 문자열을 그대로 유지합니다.
 */
public enum AvatarSlot {

    // 상품 슬롯과 장착 대상 슬롯의 일치 여부를 판단하는 기준입니다.
    HEAD,
    FACE,
    BODY,
    ACCESSORY
}