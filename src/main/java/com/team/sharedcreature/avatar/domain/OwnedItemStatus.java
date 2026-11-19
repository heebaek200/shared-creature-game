package com.team.sharedcreature.avatar.domain;

/**
 * 사용자가 보유한 아이템의 사용·회수 상태입니다.
 *
 * Enum 이름은 기존 DB 저장값과 API 응답 문자열을 그대로 유지합니다.
 */
public enum OwnedItemStatus {

    // 한 번도 사용하지 않은 상태이며, 다른 환불 조건도 충족해야 환불할 수 있습니다.
    OWNED_UNUSED,

    // 최초 장착으로 사용이 확정된 상태이며, 장착을 해제해도 유지됩니다.
    OWNED_USED,

    // 환불 완료 등으로 회수되어 더 이상 장착할 수 없는 상태입니다.
    REVOKED
}