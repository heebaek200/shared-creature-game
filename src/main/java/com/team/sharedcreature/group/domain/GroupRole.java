package com.team.sharedcreature.group.domain;

/**
 * 그룹 멤버십에 부여되는 역할입니다.
 * 기존 DB와 API의 PRIMARY·DEPUTY·MEMBER 문자열을 유지합니다.
 */
public enum GroupRole {

    // 그룹의 주 관리자입니다.
    PRIMARY,

    // 주 관리자가 지정한 부 관리자입니다.
    DEPUTY,

    // 그룹의 일반 구성원입니다.
    MEMBER
}