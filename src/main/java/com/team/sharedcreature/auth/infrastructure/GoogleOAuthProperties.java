package com.team.sharedcreature.auth.infrastructure;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 환경변수로 전달받는 Google OAuth 공개·비밀 설정입니다.
 *
 * @param clientId Google OAuth Client ID
 * @param clientSecret Google OAuth Client Secret
 * @param redirectUri 등록된 Callback URI
 */
@ConfigurationProperties("oauth.google")
public record GoogleOAuthProperties(String clientId, String clientSecret, String redirectUri) {
}
