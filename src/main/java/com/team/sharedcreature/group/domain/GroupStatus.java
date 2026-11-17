package com.team.sharedcreature.group.domain;

/**
 * 그룹의 활성 상태입니다.
 * 기존 DB의 ACTIVE·INACTIVE 문자열을 유지합니다.
 */
public enum GroupStatus {

    // 구성원이 소속되어 활동하는 그룹입니다.
    ACTIVE,

    // 마지막 구성원이 탈퇴하여 비활성화된 그룹입니다.
    INACTIVE
}