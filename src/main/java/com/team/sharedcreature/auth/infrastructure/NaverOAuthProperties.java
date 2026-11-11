package com.team.sharedcreature.auth.infrastructure;

import org.springframework.boot.context.properties.ConfigurationProperties;


/**
 * 환경변수로 전달받는 Naver OAuth 공개·비밀 설정입니다.
 *
 * @param clientId Naver OAuth Client ID
 * @param clientSecret Naver OAuth Client Secret
 * @param redirectUri 등록된 Callback URI
 */
@ConfigurationProperties("oauth.naver")
public record NaverOAuthProperties(String clientId, String clientSecret, String redirectUri) {
}
