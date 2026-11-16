package com.team.sharedcreature.auth.domain;

/**
 * OAuth 로그인 시도를 시작한 목적입니다.
 * 기존 DB의 LOGIN·INVITE 문자열을 유지합니다.
 */
public enum OauthLoginIntent {

    // 일반 로그인으로 시작한 인증입니다.
    LOGIN,

    // 그룹 초대 문맥을 포함하여 시작한 인증입니다.
    INVITE
}