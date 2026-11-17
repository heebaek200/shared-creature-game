package com.team.sharedcreature.group.domain;

/**
 * 그룹 초대 링크의 사용 가능 상태입니다.
 * 기존 DB의 ACTIVE·INVALIDATED 문자열을 유지합니다.
 */
public enum GroupInviteLinkStatus {

    // 초대 수락과 가입 검증에 사용할 수 있는 링크입니다.
    ACTIVE,

    // 재발급 또는 그룹 종료로 무효화된 링크입니다.
    INVALIDATED
}
