package com.team.sharedcreature._core.security;

import com.team.sharedcreature._core.api.ErrorResponseFactory;
import com.team.sharedcreature._core.exception.RestApiExceptionHandler;
import com.team.sharedcreature._core.web.RequestIdFilter;
import com.team.sharedcreature.auth.application.UserActivityService;
import com.team.sharedcreature.auth.domain.User;
import com.team.sharedcreature.auth.infrastructure.UserRepository;
import com.team.sharedcreature.group.domain.GroupMembership;
import com.team.sharedcreature.group.infrastructure.GroupMembershipRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.test.context.web.WebAppConfiguration;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;
import tools.jackson.databind.ObjectMapper;

import java.time.Clock;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringJUnitConfig(SecurityFilterChainTest.TestConfiguration.class)
@WebAppConfiguration
class SecurityFilterChainTest {

    @Autowired
    private WebApplicationContext context;

    @Autowired
    @Qualifier("requestIdFilterRegistration")
    private FilterRegistrationBean<RequestIdFilter> requestIdFilterRegistration;

    @Autowired
    @Qualifier("sessionAuthenticationFilterRegistration")
    private FilterRegistrationBean<SessionAuthenticationFilter> sessionAuthenticationFilterRegistration;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private GroupMembershipRepository membershipRepository;

    private MockMvc mockMvc;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private GroupMembershipRepository membershipRepository;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context)
                .apply(springSecurity())
                .build();
    }

    @Test
    void 인증_정보가_없으면_401_AUTH_REQUIRED와_동일한_Request_ID를_반환한다() throws Exception {
        mockMvc.perform(get("/api/v1/protected").header("X-Request-Id", "auth-required"))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string("X-Request-Id", "auth-required"))
                .andExpect(jsonPath("$.code").value("AUTH_REQUIRED"))
                .andExpect(jsonPath("$.traceId").value("auth-required"));
    }

    @Test
    void 커스텀_Filter는_Servlet에_중복_등록하지_않는다() {
        assertThat(requestIdFilterRegistration.isEnabled()).isFalse();
        assertThat(sessionAuthenticationFilterRegistration.isEnabled()).isFalse();
    }

    @Test
    void 복원할_수_없는_세션은_401_SESSION_EXPIRED로_응답한다() throws Exception {
        mockMvc.perform(get("/api/v1/protected")
                        .header("X-Request-Id", "session-expired")
                        .with(request -> {
                            request.setRequestedSessionId("expired-session");
                            request.setRequestedSessionIdValid(false);
                            return request;
                        }))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string("X-Request-Id", "session-expired"))
                .andExpect(jsonPath("$.code").value("SESSION_EXPIRED"))
                .andExpect(jsonPath("$.traceId").value("session-expired"));
    }

    @Test
    void CSRF_오류도_Request_ID와_공통_Envelope를_반환한다() throws Exception {
        mockMvc.perform(post("/api/v1/protected")
                        .session(authenticatedSession(1L, "MEMBER"))
                        .header("X-Request-Id", "csrf-invalid"))
                .andExpect(status().isForbidden())
                .andExpect(header().string("X-Request-Id", "csrf-invalid"))
                .andExpect(jsonPath("$.code").value("CSRF_INVALID"))
                .andExpect(jsonPath("$.traceId").value("csrf-invalid"));
    }

    @Test
    void 만료_세션의_상태_변경은_CSRF보다_SESSION_EXPIRED를_우선한다() throws Exception {
        mockMvc.perform(post("/api/v1/protected")
                        .header("X-Request-Id", "expired-before-csrf")
                        .with(request -> {
                            request.setRequestedSessionId("expired-session");
                            request.setRequestedSessionIdValid(false);
                            return request;
                        }))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("SESSION_EXPIRED"))
                .andExpect(jsonPath("$.traceId").value("expired-before-csrf"));
    }

    @Test
    void 역할이_부족하면_403_ROLE_FORBIDDEN으로_응답한다() throws Exception {
        mockMvc.perform(get("/api/v1/admin/test")
                        .session(memberSession())
                        .header("X-Request-Id", "role-forbidden"))
                .andExpect(status().isForbidden())
                .andExpect(header().string("X-Request-Id", "role-forbidden"))
                .andExpect(jsonPath("$.code").value("ROLE_FORBIDDEN"))
                .andExpect(jsonPath("$.traceId").value("role-forbidden"));
    }

    @Test
    void 일반_그룹원은_활성_크리처_종을_조회할_수_있다() throws Exception {
        mockMvc.perform(get("/api/v1/creature-species")
                        .session(authenticatedSession(3L, "MEMBER")))
                .andExpect(status().isOk());
    }

    @Test
    void 일반_그룹원은_크리처_종을_선택할_수_없다() throws Exception {
        mockMvc.perform(put("/api/v1/groups/me/seasons/current/creature-selection")
                        .session(authenticatedSession(4L, "MEMBER"))
                        .with(csrf()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ROLE_FORBIDDEN"));
    }

    @Test
    void 부_관리자는_크리처_종을_선택할_수_있다() throws Exception {
        mockMvc.perform(put("/api/v1/groups/me/seasons/current/creature-selection")
                        .session(authenticatedSession(5L, "DEPUTY"))
                        .with(csrf()))
                .andExpect(status().isOk());
    }

    @Test
    void 인증된_사용자의_미등록_API는_404_RESOURCE_NOT_FOUND로_응답한다() throws Exception {
        mockMvc.perform(get("/api/v1/not-found")
                        .session(memberSession())
                        .header("X-Request-Id", "not-found"))
                .andExpect(status().isNotFound())
                .andExpect(header().string("X-Request-Id", "not-found"))
                .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"))
                .andExpect(jsonPath("$.traceId").value("not-found"));
    }

    /** 실제 세션 인증 Filter가 복원할 수 있는 MEMBER 세션을 구성합니다. */
    private MockHttpSession memberSession() {
        User account = mock(User.class);
        GroupMembership membership = mock(GroupMembership.class);
        when(account.getId()).thenReturn(1L);
        when(account.getStatus()).thenReturn("ACTIVE");
        when(account.getGlobalRole()).thenReturn("USER");
        when(membership.getRole()).thenReturn("MEMBER");
        when(userRepository.findById(1L)).thenReturn(Optional.of(account));
        when(membershipRepository.findByUserIdAndStatus(1L, "ACTIVE"))
                .thenReturn(Optional.of(membership));
        MockHttpSession session = new MockHttpSession();
        session.setAttribute(
                SessionAuthenticationFilter.LOGIN_USER,
                new LoginUser(1L, "member", Set.of("MEMBER"))
        );
        return session;
    }

    @Configuration
    @EnableWebMvc
    @EnableWebSecurity
    @Import(SecurityConfiguration.class)
    static class TestConfiguration {

        @Bean
        Clock clock() {
            return Clock.systemUTC();
        }

        @Bean
        ObjectMapper objectMapper() {
            return new ObjectMapper();
        }

        @Bean
        ErrorResponseFactory errorResponseFactory(Clock clock) {
            return new ErrorResponseFactory(clock);
        }

        @Bean
        SecurityErrorWriter securityErrorWriter(ObjectMapper objectMapper, ErrorResponseFactory responseFactory) {
            return new SecurityErrorWriter(objectMapper, responseFactory);
        }

        @Bean
        RequestIdFilter requestIdFilter() {
            return new RequestIdFilter();
        }

        @Bean
        SessionAuthenticationFilter sessionAuthenticationFilter(
                UserRepository userRepository,
                GroupMembershipRepository membershipRepository
        ) {
            return new SessionAuthenticationFilter(userRepository, membershipRepository, mock(UserActivityService.class));
        }

        @Bean
        UserRepository userRepository() {
            return mock(UserRepository.class);
        }

        @Bean
        GroupMembershipRepository groupMembershipRepository() {
            return mock(GroupMembershipRepository.class);
        }

        @Bean
        RestApiExceptionHandler restApiExceptionHandler(ErrorResponseFactory responseFactory) {
            return new RestApiExceptionHandler(responseFactory);
        }

        @Bean
        TestController testController() {
            return new TestController();
        }
    }

    @RestController
    static class TestController {

        @GetMapping("/api/v1/protected")
        void protectedGet() {
        }

        @PostMapping("/api/v1/protected")
        void protectedPost() {
        }

        @GetMapping("/api/v1/creature-species")
        void creatureSpecies() {
        }

        @org.springframework.web.bind.annotation.PutMapping(
                "/api/v1/groups/me/seasons/current/creature-selection"
        )
        void selectCreatureSpecies() {
        }

        @GetMapping("/api/v1/admin/test")
        void admin() {
        }
    }
}
