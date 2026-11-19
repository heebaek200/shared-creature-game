package com.team.sharedcreature.group.domain;

/** 그룹 회원 강퇴 이력의 조치 유형입니다. 기존 DB 저장 코드를 유지합니다. */
public enum ModerationActionType {
    // 그룹 관리자의 비활동 회원 강퇴
    REMOVE_INACTIVE,
    // 전체 관리자의 신고 심사에 따른 강퇴
    REMOVE_REPORTED
}
