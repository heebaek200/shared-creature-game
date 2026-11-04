package com.team.sharedcreature._core.security;

import com.team.sharedcreature.auth.application.UserActivityService;
import com.team.sharedcreature.auth.domain.User;
import com.team.sharedcreature.auth.infrastructure.UserRepository;
import com.team.sharedcreature.group.domain.GroupMembership;
import com.team.sharedcreature.group.infrastructure.GroupMembershipRepository;
import jakarta.servlet.FilterChain;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SessionAuthenticationFilterTest {

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void activeMembershipRefreshesSessionRoleFromDatabase() throws Exception {
        UserRepository userRepository = mock(UserRepository.class);
        GroupMembershipRepository membershipRepository = mock(GroupMembershipRepository.class);
        User user = mock(User.class);
        GroupMembership membership = mock(GroupMembership.class);
        when(userRepository.findById(1L)).thenReturn(Optional.of(user));
        when(user.getId()).thenReturn(1L);
        when(user.getStatus()).thenReturn("ACTIVE");
        when(user.getGlobalRole()).thenReturn("USER");
        when(membershipRepository.findByUserIdAndStatus(1L, "ACTIVE")).thenReturn(Optional.of(membership));
        when(membership.getRole()).thenReturn("PRIMARY");

        MockHttpSession session = new MockHttpSession();
        session.setAttribute(SessionAuthenticationFilter.LOGIN_USER, new LoginUser(1L, "새싹지기", Set.of()));
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setSession(session);
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        new SessionAuthenticationFilter(
                userRepository,
                membershipRepository,
                mock(UserActivityService.class)
        ).doFilter(request, response, chain);

        LoginUser refreshed = (LoginUser) session.getAttribute(SessionAuthenticationFilter.LOGIN_USER);
        assertThat(refreshed.roles()).containsExactly("PRIMARY");
        assertThat(SecurityContextHolder.getContext().getAuthentication().getAuthorities())
                .extracting("authority")
                .containsExactly("ROLE_PRIMARY");
        verify(chain).doFilter(request, response);
    }

    @Test
    void superAdmin은_다음_요청에서도_활성_그룹_역할을_함께_유지한다() throws Exception {
        UserRepository userRepository = mock(UserRepository.class);
        GroupMembershipRepository membershipRepository = mock(GroupMembershipRepository.class);
        User user = mock(User.class);
        GroupMembership membership = mock(GroupMembership.class);
        when(userRepository.findById(1L)).thenReturn(Optional.of(user));
        when(user.getId()).thenReturn(1L);
        when(user.getStatus()).thenReturn("ACTIVE");
        when(user.getGlobalRole()).thenReturn("SUPER_ADMIN");
        when(membershipRepository.findByUserIdAndStatus(1L, "ACTIVE")).thenReturn(Optional.of(membership));
        when(membership.getRole()).thenReturn("MEMBER");

        MockHttpSession session = new MockHttpSession();
        session.setAttribute(
                SessionAuthenticationFilter.LOGIN_USER,
                new LoginUser(1L, "전체관리자", Set.of("SUPER_ADMIN", "MEMBER"))
        );
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setSession(session);

        new SessionAuthenticationFilter(userRepository, membershipRepository, mock(UserActivityService.class))
                .doFilter(request, new MockHttpServletResponse(), mock(FilterChain.class));

        LoginUser refreshed = (LoginUser) session.getAttribute(SessionAuthenticationFilter.LOGIN_USER);
        assertThat(refreshed.roles()).containsExactlyInAnyOrder("SUPER_ADMIN", "MEMBER");
        assertThat(SecurityContextHolder.getContext().getAuthentication().getAuthorities())
                .extracting("authority")
                .containsExactlyInAnyOrder("ROLE_SUPER_ADMIN", "ROLE_MEMBER");
    }

    @Test
    void inactiveAccountRemovesAuthenticatedSessionState() throws Exception {
        UserRepository userRepository = mock(UserRepository.class);
        GroupMembershipRepository membershipRepository = mock(GroupMembershipRepository.class);
        User user = mock(User.class);
        when(userRepository.findById(1L)).thenReturn(Optional.of(user));
        when(user.getStatus()).thenReturn("SUSPENDED");

        MockHttpSession session = new MockHttpSession();
        session.setAttribute(SessionAuthenticationFilter.LOGIN_USER, new LoginUser(1L, "새싹지기", Set.of("PRIMARY")));
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setSession(session);

        new SessionAuthenticationFilter(userRepository, membershipRepository, mock(UserActivityService.class))
                .doFilter(request, new MockHttpServletResponse(), mock(FilterChain.class));

        assertThat(session.getAttribute(SessionAuthenticationFilter.LOGIN_USER)).isNull();
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }

    @Test
    void 복원할_수_없는_세션_ID는_만료_표시를_남긴다() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRequestedSessionId("expired-session");
        request.setRequestedSessionIdValid(false);
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        new SessionAuthenticationFilter(mock(UserRepository.class), mock(GroupMembershipRepository.class), mock(UserActivityService.class))
                .doFilter(request, response, chain);

        assertThat(request.getAttribute(SessionAuthenticationFilter.SESSION_EXPIRED)).isEqualTo(true);
        verify(chain).doFilter(request, response);
    }

    /**
     * 정상 응답을 받은 인증 API 요청만 사용자 활동으로 기록하는지 확인합니다.
     *
     * 유효한 인증 세션으로 요청을 처리한 뒤 성공 상태 코드를 설정합니다.
     * 요청 완료 후 활동 기록 Service가 정확히 한 번 호출되어야 합니다.
     */
    @Test
    void 성공한_인증_API_요청은_활동_시각을_기록한다() throws Exception {
        UserRepository userRepository = mock(UserRepository.class);
        GroupMembershipRepository membershipRepository =
                mock(GroupMembershipRepository.class);
        UserActivityService activityService = mock(UserActivityService.class);

        // 정상 인증된 사용자를 준비합니다.
        User user = mock(User.class);
        when(userRepository.findById(1L)).thenReturn(Optional.of(user));
        when(user.getId()).thenReturn(1L);
        when(user.getStatus()).thenReturn("ACTIVE");
        when(user.getGlobalRole()).thenReturn("USER");

        MockHttpSession session = new MockHttpSession();
        session.setAttribute(
                SessionAuthenticationFilter.LOGIN_USER,
                new LoginUser(1L, "테스트", Set.of("MEMBER"))
        );

        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRequestURI("/api/v1/me");
        request.setSession(session);

        MockHttpServletResponse response = new MockHttpServletResponse();

        // 실제 API가 정상 처리된 상황을 모의합니다.
        FilterChain chain = (req, res) ->
                ((HttpServletResponse) res).setStatus(200);

        new SessionAuthenticationFilter(
                userRepository,
                membershipRepository,
                activityService
        ).doFilter(request, response, chain);

        verify(activityService).recordSuccessfulRequest(1L);
    }
}
