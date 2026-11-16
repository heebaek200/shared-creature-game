package com.team.sharedcreature.auth.infrastructure;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.team.sharedcreature._core.exception.BusinessException;
import com.team.sharedcreature._core.exception.ErrorCode;
import com.team.sharedcreature.auth.application.SocialLoginClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;

/**
 * Google Authorization Code 연동 URL과 후속 교환 확장 지점을 제공합니다.
 */
@Component
@EnableConfigurationProperties(GoogleOAuthProperties.class)
public class GoogleLoginClient implements SocialLoginClient {

    private static final String TOKEN_ENDPOINT = "https://oauth2.googleapis.com/token";
    private static final String USER_INFO_ENDPOINT = "https://openidconnect.googleapis.com/v1/userinfo";
    private static final String JWK_SET_URI = "https://www.googleapis.com/oauth2/v3/certs";
    private static final List<String> VALID_ISSUERS = List.of("https://accounts.google.com", "accounts.google.com");

    private final GoogleOAuthProperties properties;
    private final RestClient restClient;
    private final JwtDecoder jwtDecoder;

    /** Google OAuth 설정과 HTTP Client를 구성합니다. */
    @Autowired
    public GoogleLoginClient(GoogleOAuthProperties properties, RestClient.Builder builder) {
        this(properties, builder.build(), createJwtDecoder(properties));
    }

    GoogleLoginClient(GoogleOAuthProperties properties, RestClient restClient, JwtDecoder jwtDecoder) {
        this.properties = properties;
        this.restClient = restClient;
        this.jwtDecoder = jwtDecoder;
    }

    @Override
    public String provider() {
        return "GOOGLE";
    }

    /** Google 공식 Authorization Endpoint URL을 생성합니다. */
    @Override
    public String authorizationUrl(String state, String nonce) {
        requireConfigured();
        URI uri = UriComponentsBuilder.fromUriString("https://accounts.google.com/o/oauth2/v2/auth")
                .queryParam("client_id", properties.clientId())
                .queryParam("redirect_uri", properties.redirectUri())
                .queryParam("response_type", "code")
                .queryParam("scope", "openid profile email")
                .queryParam("state", state)
                .queryParam("nonce", nonce)
                .queryParam("prompt", "select_account")
                .build()
                .encode()
                .toUri();
        return uri.toString();
    }

    /**
     * 인가 코드를 Token으로 교환하고 검증된 Google 사용자 프로필을 반환합니다.
     */
    @Override
    public SocialProfile exchange(String code, String expectedNonce, String state) {
        requireConfigured();
        if (!StringUtils.hasText(code) || !StringUtils.hasText(expectedNonce)) {
            throw invalidToken("Google 인증 요청 정보가 올바르지 않습니다.");
        }

        try {
            GoogleTokenResponse token = exchangeCode(code);
            if (token == null || !StringUtils.hasText(token.accessToken()) || !StringUtils.hasText(token.idToken())) {
                throw providerUnavailable("Google Token 응답이 올바르지 않습니다.");
            }

            Jwt idToken = jwtDecoder.decode(token.idToken());
            verifyNonce(idToken.getClaimAsString("nonce"), expectedNonce);

            GoogleUserInfo userInfo = loadUserInfo(token.accessToken());
            if (userInfo == null || !StringUtils.hasText(idToken.getSubject())
                    || !StringUtils.hasText(userInfo.subject())
                    || !StringUtils.hasText(userInfo.email())) {
                throw providerUnavailable("Google 사용자 정보 응답이 올바르지 않습니다.");
            }
            if (!MessageDigest.isEqual(
                    idToken.getSubject().getBytes(StandardCharsets.UTF_8),
                    userInfo.subject().getBytes(StandardCharsets.UTF_8))) {
                throw invalidToken("Google 사용자 식별자가 일치하지 않습니다.");
            }
            return new SocialProfile(
                    userInfo.subject(),
                    userInfo.email(),
                    userInfo.emailVerified(),
                    userInfo.name()
            );
        } catch (BusinessException exception) {
            throw exception;
        } catch (JwtException exception) {
            throw invalidToken("Google ID Token 검증에 실패했습니다.");
        } catch (RestClientResponseException exception) {
            if (exception.getStatusCode().is4xxClientError()) {
                throw invalidToken("Google 인가 코드 또는 Token이 유효하지 않습니다.");
            }
            throw providerUnavailable("Google 인증 서버가 요청을 처리하지 못했습니다.");
        } catch (RestClientException exception) {
            throw providerUnavailable("Google 인증 서버와 통신하지 못했습니다.");
        }
    }

    private GoogleTokenResponse exchangeCode(String code) {
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("code", code);
        form.add("client_id", properties.clientId());
        form.add("client_secret", properties.clientSecret());
        form.add("redirect_uri", properties.redirectUri());
        form.add("grant_type", "authorization_code");

        return restClient.post()
                .uri(TOKEN_ENDPOINT)
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .body(form)
                .retrieve()
                .body(GoogleTokenResponse.class);
    }

    private GoogleUserInfo loadUserInfo(String accessToken) {
        return restClient.get()
                .uri(USER_INFO_ENDPOINT)
                .headers(headers -> headers.setBearerAuth(accessToken))
                .retrieve()
                .body(GoogleUserInfo.class);
    }

    private void verifyNonce(String actualNonce, String expectedNonce) {
        if (!StringUtils.hasText(actualNonce) || !MessageDigest.isEqual(
                actualNonce.getBytes(StandardCharsets.UTF_8),
                expectedNonce.getBytes(StandardCharsets.UTF_8))) {
            throw invalidToken("Google 인증 nonce가 일치하지 않습니다.");
        }
    }

    private static JwtDecoder createJwtDecoder(GoogleOAuthProperties properties) {
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withJwkSetUri(JWK_SET_URI).build();
        OAuth2TokenValidator<Jwt> issuerAndAudience = token -> {
            boolean issuerValid = VALID_ISSUERS.contains(token.getIssuer() == null ? null : token.getIssuer().toString());
            boolean audienceValid = token.getAudience().contains(properties.clientId());
            if (issuerValid && audienceValid) {
                return OAuth2TokenValidatorResult.success();
            }
            return OAuth2TokenValidatorResult.failure(new OAuth2Error(
                    "invalid_token", "Google ID Token issuer 또는 audience가 올바르지 않습니다.", null));
        };
        decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(
                new JwtTimestampValidator(),
                issuerAndAudience
        ));
        return decoder;
    }

    private BusinessException invalidToken(String message) {
        return new BusinessException(ErrorCode.OAUTH_ID_TOKEN_INVALID, message);
    }

    private BusinessException providerUnavailable(String message) {
        return new BusinessException(ErrorCode.OAUTH_PROVIDER_UNAVAILABLE, message);
    }

    private void requireConfigured() {
        if (properties.clientId() == null || properties.clientId().isBlank()
                || properties.clientSecret() == null || properties.clientSecret().isBlank()) {
            throw new BusinessException(
                    ErrorCode.OAUTH_PROVIDER_UNAVAILABLE,
                    "Google OAuth 환경변수가 설정되지 않았습니다."
            );
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record GoogleTokenResponse(
            @JsonProperty("access_token") String accessToken,
            @JsonProperty("id_token") String idToken
    ) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record GoogleUserInfo(
            @JsonProperty("sub") String subject,
            String email,
            @JsonProperty("email_verified") boolean emailVerified,
            String name
    ) {
    }
}
