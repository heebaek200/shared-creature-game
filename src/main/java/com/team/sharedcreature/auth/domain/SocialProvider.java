package com.team.sharedcreature.auth.domain;

/**
 * 소셜 계정과 OAuth 로그인 시도에 사용하는 인증 공급자입니다.
 * 기존 DB와 API의 대문자 공급자 코드를 유지합니다.
 */
public enum SocialProvider {

    // Google 인증 공급자입니다.
    GOOGLE,

    // 설계 문서에 정의된 Naver 인증 공급자입니다.
    NAVER
}