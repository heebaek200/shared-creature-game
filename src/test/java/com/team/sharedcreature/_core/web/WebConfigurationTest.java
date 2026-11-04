package com.team.sharedcreature._core.web;

import com.team.sharedcreature.auth.api.AuthController;
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
