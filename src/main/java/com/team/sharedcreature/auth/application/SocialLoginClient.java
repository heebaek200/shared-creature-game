package com.team.sharedcreature.auth.application;

/**
 * 소셜 공급자 HTTP 연동을 인증 업무 흐름에서 분리합니다.
 */
public interface SocialLoginClient {

    /** 공급자 동의 화면 URL을 생성합니다. */
    String authorizationUrl(String state, String nonce);

    /** 인가 코드를 검증된 공급자 프로필로 교환합니다. */
    SocialProfile exchange(String code, String expectedNonce);

    /** 공급자가 검증해 반환한 최소 사용자 프로필입니다. */
    record SocialProfile(String providerUserId, String email, boolean emailVerified, String suggestedNickname) {
    }
}
