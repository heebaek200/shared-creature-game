package com.team.sharedcreature.auth.infrastructure;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.team.sharedcreature.auth.application.SocialLoginClient;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import com.team.sharedcreature._core.exception.BusinessException;
import com.team.sharedcreature._core.exception.ErrorCode;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.util.UriComponentsBuilder;


import java.net.URI;

@Component
@EnableConfigurationProperties(NaverOAuthProperties.class)
public class NaverLoginClient implements SocialLoginClient {

    private final NaverOAuthProperties properties;
    private final RestClient restClient;

    public NaverLoginClient(
            NaverOAuthProperties properties,
            RestClient.Builder restClientBuilder
    ) {
        this.properties = properties;
        this.restClient = restClientBuilder.build();
    }

    @Override
    public String provider() {
        return "NAVER";
    }

    @Override
    public String authorizationUrl(String state, String nonce) {
        requireConfigured();

        URI uri = UriComponentsBuilder
                .fromUriString("https://nid.naver.com/oauth2.0/authorize")
                .queryParam("response_type", "code")
                .queryParam("client_id", properties.clientId())
                .queryParam("redirect_uri", properties.redirectUri())
                .queryParam("state", state)
                .build()
                .encode()
                .toUri();

        return uri.toString();
    }


    @Override
    public SocialProfile exchange(String code, String expectedNonce, String state) {
        requireConfigured();

        if (code == null || code.isBlank()
                || state == null || state.isBlank()) {
            throw invalidToken("Naver oAuth 인증 정보가 누락되었습니다");
        }


        try {
            LinkedMultiValueMap<String, String> form = new LinkedMultiValueMap<>();
            form.add("grant_type", "authorization_code");
            form.add("client_id", properties.clientId());
            form.add("client_secret", properties.clientSecret());
            form.add("code", code);
            form.add("state", state);

            NaverTokenResponse tokenResponse = restClient.post()
                    .uri("https://nid.naver.com/oauth2.0/token")
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .body(form)
                    .retrieve()
                    .body(NaverTokenResponse.class);

            if (tokenResponse != null
                    && tokenResponse.error() != null
                    && !tokenResponse.error().isBlank()) {
                throw invalidToken("Naver Token 발급에 실패했습니다.");
            }

            if (tokenResponse == null
                    || tokenResponse.accessToken() == null
                    || tokenResponse.accessToken().isBlank()) {
                throw providerUnavailable("Naver Access Token을 발급받지 못했습니다");
            }

            NaverProfileResponse profileResponse = loadUserInfo(tokenResponse.accessToken());

            if (profileResponse == null
                    || !"00".equals(profileResponse.resultcode())
                    || profileResponse.response() == null) {
                throw providerUnavailable("Naver 사용자 정보 조회에 실패했습니다");
            }

            NaverUserInfo userInfo = profileResponse.response();

            if (userInfo.id() == null || userInfo.id().isBlank()
                    || userInfo.email() == null || userInfo.email().isBlank()) {
                throw providerUnavailable("Naver 사용자 식별자 또는 이메일이 누락되었습니다");
            }

            return new SocialProfile(
                    userInfo.id(),
                    userInfo.email(),
                    false,
                    userInfo.nickname() != null && !userInfo.nickname().isBlank()
                            ? userInfo.nickname() : userInfo.name()
            );

        } catch (BusinessException exception) {
            throw exception;
        } catch (RestClientResponseException exception) {
            if (exception.getStatusCode().is4xxClientError()) {
                throw invalidToken("Naver 인가 코드 또는 Token이 유효하지 않습니다");
            }

            throw providerUnavailable("Naver 인증 서버가 요청을 처리하지 못했습니다");
        } catch (RestClientException exception) {
            throw providerUnavailable("Naver 인증 서버와 통신하지 못했습니다");
        }


    }

    private NaverProfileResponse loadUserInfo(String accessToken) {
        return restClient.get()
                .uri("https://openapi.naver.com/v1/nid/me")
                .headers(headers -> headers.setBearerAuth(accessToken))
                .retrieve()
                .body(NaverProfileResponse.class);
    }

    private BusinessException invalidToken(String message) {
        return new BusinessException(ErrorCode.OAUTH_ID_TOKEN_INVALID, message);
    }

    private BusinessException providerUnavailable(String message) {
        return new BusinessException(ErrorCode.OAUTH_PROVIDER_UNAVAILABLE, message);
    }

    private void requireConfigured() {
        if (properties.clientId() == null || properties.clientId().isBlank()
                || properties.clientSecret() == null || properties.clientSecret().isBlank()
                || properties.redirectUri() == null || properties.redirectUri().isBlank()) {
            throw providerUnavailable("Naver OAuth 환경변수가 설정되지 않았습니다");
        }
    }

    private record NaverTokenResponse(
            @JsonProperty("access_token")
            String accessToken,
            @JsonProperty("token_type")
            String tokenType,
            @JsonProperty("refresh_token")
            String refreshToken,
            @JsonProperty("expires_in")
            Integer expiresIn,
            String error,
            @JsonProperty("error_description")
            String errorDescription
    ) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record NaverProfileResponse(
            String resultcode,
            String message,
            NaverUserInfo response
    ) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record NaverUserInfo(
            String id,
            String email,
            String nickname,
            String name
    ) {
    }
}
