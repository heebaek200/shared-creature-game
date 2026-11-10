package com.team.sharedcreature.auth.infrastructure;

import com.team.sharedcreature._core.exception.BusinessException;
import com.team.sharedcreature.auth.application.SocialLoginClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.restclient.autoconfigure.RestClientAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.ExpectedCount.once;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class GoogleLoginClientTest {

    private MockRestServiceServer server;
    private GoogleOAuthProperties properties;

    @BeforeEach
    void setUp() {
        properties = new GoogleOAuthProperties(
                "client-id",
                "client-secret",
                "http://localhost:5173/oauth2/callback/google"
        );
    }

    @Test
    void authorizationUrlEncodesSpaceDelimitedScope() {
        GoogleLoginClient client = client(token -> validJwt("expected-nonce"));

        String authorizationUrl = client.authorizationUrl("state-value", "expected-nonce");

        assertThat(authorizationUrl)
                .contains("scope=openid%20profile%20email")
                .contains("state=state-value")
                .contains("nonce=expected-nonce");
    }

    @Test
    void springContextCreatesGoogleLoginClientWithInjectionConstructor() {
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(RestClientAutoConfiguration.class))
                .withUserConfiguration(GoogleLoginClient.class)
                .withPropertyValues(
                        "oauth.google.client-id=client-id",
                        "oauth.google.client-secret=client-secret",
                        "oauth.google.redirect-uri=http://localhost:5173/oauth2/callback/google"
                )
                .run(context -> assertThat(context).hasSingleBean(GoogleLoginClient.class));
    }

    @Test
    void exchangeReturnsProfileAfterTokenAndUserInfoValidation() {
        GoogleLoginClient client = client(token -> validJwt("expected-nonce"));
        server.expect(once(), requestTo("https://oauth2.googleapis.com/token"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_FORM_URLENCODED))
                .andExpect(content().string(org.hamcrest.Matchers.allOf(
                        org.hamcrest.Matchers.containsString("code=authorization-code"),
                        org.hamcrest.Matchers.containsString("client_id=client-id"),
                        org.hamcrest.Matchers.containsString("redirect_uri=http%3A%2F%2Flocalhost%3A5173%2Foauth2%2Fcallback%2Fgoogle"),
                        org.hamcrest.Matchers.containsString("grant_type=authorization_code")
                )))
                .andRespond(withSuccess(
                        "{\"access_token\":\"access-token\",\"id_token\":\"signed-id-token\","
                                + "\"token_type\":\"Bearer\",\"expires_in\":3600}",
                        MediaType.APPLICATION_JSON
                ));
        server.expect(once(), requestTo("https://openidconnect.googleapis.com/v1/userinfo"))
                .andExpect(method(HttpMethod.GET))
                .andExpect(header("Authorization", "Bearer access-token"))
                .andRespond(withSuccess(
                        "{\"sub\":\"google-user-1\",\"email\":\"user@example.com\","
                                + "\"email_verified\":true,\"name\":\"공유 생명체\","
                                + "\"picture\":\"https://example.com/avatar.png\"}",
                        MediaType.APPLICATION_JSON
                ));

        SocialLoginClient.SocialProfile profile = client.exchange("authorization-code", "expected-nonce");

        assertThat(profile.providerUserId()).isEqualTo("google-user-1");
        assertThat(profile.email()).isEqualTo("user@example.com");
        assertThat(profile.emailVerified()).isTrue();
        assertThat(profile.suggestedNickname()).isEqualTo("공유 생명체");
        server.verify();
    }

    @Test
    void exchangeRejectsMismatchedNonceBeforeUserInfoRequest() {
        GoogleLoginClient client = client(token -> validJwt("different-nonce"));
        server.expect(once(), requestTo("https://oauth2.googleapis.com/token"))
                .andRespond(withSuccess(
                        "{\"access_token\":\"access-token\",\"id_token\":\"signed-id-token\"}",
                        MediaType.APPLICATION_JSON
                ));

        assertThatThrownBy(() -> client.exchange("authorization-code", "expected-nonce"))
                .isInstanceOfSatisfying(BusinessException.class, exception -> {
                    assertThat(exception.status().value()).isEqualTo(401);
                    assertThat(exception.code()).isEqualTo("OAUTH_ID_TOKEN_INVALID");
                });
        server.verify();
    }

    private GoogleLoginClient client(JwtDecoder jwtDecoder) {
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        return new GoogleLoginClient(properties, builder.build(), jwtDecoder);
    }

    private Jwt validJwt(String nonce) {
        Instant now = Instant.now();
        return Jwt.withTokenValue("signed-id-token")
                .header("alg", "RS256")
                .subject("google-user-1")
                .claim("nonce", nonce)
                .issuedAt(now)
                .expiresAt(now.plusSeconds(300))
                .build();
    }
}
