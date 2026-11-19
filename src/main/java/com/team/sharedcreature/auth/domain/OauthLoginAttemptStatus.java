package com.team.sharedcreature.auth.domain;

/**
 * 일회성 OAuth 로그인 시도의 처리 상태입니다.
 * 기존 DB에 저장된 상태 문자열을 그대로 유지합니다.
 */
public enum OauthLoginAttemptStatus {

    // 생성 후 Callback에서 아직 소비하지 않은 상태입니다.
    PENDING,

    // 검증을 마치고 소비한 상태입니다.
    CONSUMED
}