package com.team.sharedcreature._core.web;

import com.team.sharedcreature.auth.api.AuthController;
import com.team.sharedcreature._core.exception.BusinessException;
import com.team.sharedcreature._core.exception.ErrorCode;
import com.team.sharedcreature.auth.application.AuthService;
import jakarta.servlet.http.HttpSession;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.test.context.web.WebAppConfiguration;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringJUnitConfig(WebConfigurationTest.TestConfiguration.class)
@WebAppConfiguration
class WebConfigurationTest {

    @Autowired
    private WebApplicationContext context;

    @Autowired
    private AuthService authService;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        reset(authService);
        mockMvc = MockMvcBuilders.webAppContextSetup(context).build();
    }

    @Test
    void OAuth_Callback은_공급자_확장_Query를_허용하고_302로_처리한다() throws Exception {
        when(authService.handleCallback(any(), any(), any(HttpSession.class))).thenReturn("/");

        mockMvc.perform(get("/oauth2/callback/google")
                        .queryParam("code", "authorization-code")
                        .queryParam("state", "verified-state")
                        .queryParam("scope", "openid email profile")
                        .queryParam("authuser", "0")
                        .queryParam("prompt", "consent"))
                .andExpect(status().isFound())
                .andExpect(header().string("Location", "/"));

        verify(authService).handleCallback(
                org.mockito.ArgumentMatchers.eq("verified-state"),
                org.mockito.ArgumentMatchers.eq("authorization-code"),
                any(HttpSession.class)
        );
    }

    @Test
    void OAuth_인증_취소는_ACCESS_DENIED로_이동한다() throws Exception {
        mockMvc.perform(get("/oauth2/callback/google")
                        .queryParam("error", "access_denied"))
                .andExpect(status().isFound())
                .andExpect(header().string("Location", "/login?oauthError=OAUTH_ACCESS_DENIED"));
    }

    @Test
    void OAuth_error와_code를_동시에_보내면_INVALID_STATE로_이동한다() throws Exception {
        mockMvc.perform(get("/oauth2/callback/google")
                        .queryParam("error", "access_denied")
                        .queryParam("code", "authorization-code"))
                .andExpect(status().isFound())
                .andExpect(header().string("Location", "/login?oauthError=OAUTH_INVALID_STATE"));
    }

    @Test
    void OAuth의_알_수_없는_공급자_오류는_AUTHENTICATION_FAILED로_이동한다() throws Exception {
        mockMvc.perform(get("/oauth2/callback/google")
                        .queryParam("error", "server_error"))
                .andExpect(status().isFound())
                .andExpect(header().string("Location", "/login?oauthError=OAUTH_AUTHENTICATION_FAILED"));
    }

    @Test
    void OAuth의_잘못된_Callback_입력은_INVALID_STATE로_이동한다() throws Exception {
        mockMvc.perform(get("/oauth2/callback/google")
                        .queryParam("code", "authorization-code"))
                .andExpect(status().isFound())
                .andExpect(header().string("Location", "/login?oauthError=OAUTH_INVALID_STATE"));
    }

    @Test
    void OAuth_State_검증_실패는_INVALID_STATE로_이동한다() throws Exception {
        when(authService.handleCallback(any(), any(), any(HttpSession.class)))
                .thenThrow(new BusinessException(ErrorCode.OAUTH_STATE_INVALID));

        mockMvc.perform(get("/oauth2/callback/google")
                        .queryParam("code", "authorization-code")
                        .queryParam("state", "invalid-state"))
                .andExpect(status().isFound())
                .andExpect(header().string("Location", "/login?oauthError=OAUTH_INVALID_STATE"));
    }

    @Test
    void OAuth_세션_불일치는_SESSION_EXPIRED로_이동한다() throws Exception {
        when(authService.handleCallback(any(), any(), any(HttpSession.class)))
                .thenThrow(new BusinessException(ErrorCode.OAUTH_SESSION_MISMATCH));

        mockMvc.perform(get("/oauth2/callback/google")
                        .queryParam("code", "authorization-code")
                        .queryParam("state", "verified-state"))
                .andExpect(status().isFound())
                .andExpect(header().string("Location", "/login?oauthError=OAUTH_SESSION_EXPIRED"));
    }

    @Test
    void OAuth_Token_검증_실패는_AUTHENTICATION_FAILED로_이동한다() throws Exception {
        when(authService.handleCallback(any(), any(), any(HttpSession.class)))
                .thenThrow(new BusinessException(ErrorCode.OAUTH_ID_TOKEN_INVALID));

        mockMvc.perform(get("/oauth2/callback/google")
                        .queryParam("code", "invalid-code")
                        .queryParam("state", "verified-state"))
                .andExpect(status().isFound())
                .andExpect(header().string("Location", "/login?oauthError=OAUTH_AUTHENTICATION_FAILED"));
    }

    @Configuration
    @EnableWebMvc
    @Import(WebConfiguration.class)
    static class TestConfiguration {

        @Bean
        StrictQueryParameterInterceptor strictQueryParameterInterceptor() {
            return new StrictQueryParameterInterceptor();
        }

        @Bean
        AuthService authService() {
            return mock(AuthService.class);
        }

        @Bean
        AuthController authController(AuthService authService) {
            return new AuthController(authService);
        }
    }
}
