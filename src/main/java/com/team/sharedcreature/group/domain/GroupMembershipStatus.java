package com.team.sharedcreature.group.domain;

/**
 * 사용자의 그룹 소속 상태입니다.
 * 기존 DB와 API의 ACTIVE·LEFT·REMOVED 문자열을 유지합니다.
 */
public enum GroupMembershipStatus {

    // 현재 그룹에 소속된 상태입니다.
    ACTIVE,

    // 사용자가 자진 탈퇴한 상태입니다.
    LEFT,

    // 비활동 또는 신고 처리로 강퇴된 상태입니다.
    REMOVED
}