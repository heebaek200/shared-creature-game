package com.team.sharedcreature.auth.domain;

/**
 * 회원 계정의 이용 상태입니다.
 *
 * Enum 이름은 기존 users.status 컬럼의 저장 문자열을 유지합니다.
 */
public enum UserStatus {

    // 계정 상태 검사를 통과해 서비스를 이용할 수 있는 활성 회원입니다.
    ACTIVE,

    // 서비스 이용이 정지된 회원입니다.
    SUSPENDED,

    // 회원 탈퇴가 처리된 계정입니다.
    WITHDRAWN
}