package com.team.sharedcreature.auth.domain;

/**
 * 계정에 부여되는 서비스 전체 범위의 역할입니다.
 *
 * Enum 이름은 기존 users.global_role 컬럼과 API 문자열을 유지합니다.
 */
public enum GlobalRole {

    // 일반 사용자이며 그룹 내 권한은 별도의 그룹 역할로 결정합니다.
    USER,

    // 서비스 전체의 관리자 기능에 접근할 수 있는 역할입니다.
    SUPER_ADMIN
}