package com.team.sharedcreature._core.security;

import com.team.sharedcreature.auth.application.UserActivityService;
import com.team.sharedcreature.auth.domain.User;
import com.team.sharedcreature.auth.infrastructure.UserRepository;
import com.team.sharedcreature.group.domain.GroupMembership;
import com.team.sharedcreature.group.infrastructure.GroupMembershipRepository;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

/**
 * STEP 4 테스트 중 재현된 세션 역할 갱신 누락을 검증합니다.
 *
 * SecurityContext에 오래된 Authentication이 이미 있더라도 DB의 최신 멤버십
 * 역할로 세션과 인증 권한이 모두 갱신되어야 합니다.
 */
class SessionRoleRefreshRegressionTest {

    /**
     * SecurityContextHolder는 스레드 로컬을 사용하므로 테스트마다 정리합니다.
     *
     * 다른 인증 테스트에 가짜 ROLE_MEMBER 상태가 전파되지 않게 합니다.
     * 테스트 간 순서 의존성을 제거합니다.
     */
    @AfterEach
    void tearDown() {
        // 스레드의 인증 정보를 제거합니다.
        SecurityContextHolder.clearContext();
    }

    /**
     * 오래된 SecurityContext가 있더라도 PRIMARY 역할이 복구되어야 합니다.
     *
     * 실제 DB 멤버십은 PRIMARY, 세션·기존 인증 객체는 MEMBER로 준비합니다.
     * 과거 조건문의 Authentication == null 검사가 역할 갱신을 막는 회귀를 포착합니다.
     */
    @Test
    void 인증객체가_이미_있어도_DB의_PRIMARY_역할로_다시_동기화한다() throws Exception {
        UserRepository userRepository = mock(UserRepository.class);
        GroupMembershipRepository membershipRepository = mock(GroupMembershipRepository.class);
        UserActivityService activityService = mock(UserActivityService.class);
        User user = mock(User.class);
        GroupMembership membership = mock(GroupMembership.class);
        when(userRepository.findById(1L)).thenReturn(Optional.of(user));
        when(user.getId()).thenReturn(1L);
        when(user.getStatus()).thenReturn("ACTIVE");
        when(user.getGlobalRole()).thenReturn("USER");
        when(membershipRepository.findByUserIdAndStatus(1L, "ACTIVE"))
                .thenReturn(Optional.of(membership));
        when(membership.getRole()).thenReturn("PRIMARY");

        // DB와 달리 세션 및 이전 인증 객체에는 MEMBER만 있습니다.
        MockHttpSession session = new MockHttpSession();
        LoginUser oldLogin = new LoginUser(1L, "테스트", Set.of("MEMBER"));
        session.setAttribute(SessionAuthenticationFilter.LOGIN_USER, oldLogin);
        SecurityContextHolder.getContext().setAuthentication(
                UsernamePasswordAuthenticationToken.authenticated(
                        oldLogin, "N/A", List.of(new SimpleGrantedAuthority("ROLE_MEMBER"))
                )
        );

        // 한 번의 HTTP 요청을 통과시키고 갱신된 역할을 확인합니다.
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setSession(session);
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);
        new SessionAuthenticationFilter(
                userRepository, membershipRepository, activityService
        ).doFilter(request, response, chain);

        LoginUser refreshed = (LoginUser) session.getAttribute(
                SessionAuthenticationFilter.LOGIN_USER);
        assertThat(refreshed.roles()).containsExactly("PRIMARY");
        assertThat(SecurityContextHolder.getContext().getAuthentication().getAuthorities())
                .extracting("authority")
                .containsExactly("ROLE_PRIMARY");
        verify(chain).doFilter(request, response);
    }
}
