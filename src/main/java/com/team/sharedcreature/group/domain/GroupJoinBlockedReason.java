package com.team.sharedcreature.group.domain;

/** 그룹 및 초대 조회 응답에 표시하는 가입 불가 사유입니다. */
public enum GroupJoinBlockedReason {
    GROUP_FULL,
    JOIN_COOLDOWN_ACTIVE,
    INVITE_INVALID,
    ALREADY_IN_GROUP
}
