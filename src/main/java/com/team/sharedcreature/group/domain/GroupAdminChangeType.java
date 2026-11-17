package com.team.sharedcreature.group.domain;

/**
 * 그룹 관리자 역할 변경 이력의 유형입니다.
 * 기존 DB의 변경 유형 코드를 그대로 유지합니다.
 */
public enum GroupAdminChangeType {

    // 그룹 생성 시 최초 주 관리자를 지정합니다.
    INITIAL_PRIMARY,

    // 기존 주 관리자의 권한을 다른 구성원에게 이전합니다.
    TRANSFER_PRIMARY,

    // 일반 구성원에게 부 관리자 권한을 부여합니다.
    GRANT_DEPUTY,

    // 부 관리자 권한을 회수하여 일반 구성원으로 변경합니다.
    REVOKE_DEPUTY
}
