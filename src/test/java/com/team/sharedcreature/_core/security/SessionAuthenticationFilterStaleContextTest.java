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
 * SecurityContext에 이전 인증 객체가 남은 상황을 검증합니다.
 *
 * 이 프로젝트에서는 HttpSession의 LOGIN_USER만 인증 복원 기준으로 사용합니다.
 * 세션 부재·비활성 계정에는 이전 권한을 제거하고 유효 회원은 역할을 갱신해야 합니다.
 */
class SessionAuthenticationFilterStaleContextTest {

    /**
     * 테스트마다 스레드 로컬의 SecurityContext를 정리합니다.
     *
     * 인증 정보가 다른 JUnit 테스트로 유출되지 않도록 막습니다.
     * 테스트 간 실행 순서에 따른 실패를 방지합니다.
     */
    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
    }

    /**
     * 로그인 세션이 없다면 오래된 PRIMARY 권한을 사용해서는 안 됩니다.
     *
     * 이전 Authentication을 의도적으로 설정한 뒤 HttpSession 없는 요청을 실행합니다.
     * 요청 종료 시 인증 객체가 비어 있어야 합니다.
     */
    @Test
    void 세션이_없는_요청은_오래된_PRIMARY_인증을_제거한다() throws Exception {
        setOldPrimaryAuthentication();

        // LOGIN_USER 속성이 없는 HTTP 요청에서 인증 복원을 실행합니다.
        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);
        new SessionAuthenticationFilter(
                mock(UserRepository.class),
                mock(GroupMembershipRepository.class),
                mock(UserActivityService.class)
        ).doFilter(request, response, chain);

        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
        verify(chain).doFilter(request, response);
    }

    /**
     * 사용자 계정이 SUSPENDED이면 세션과 이전 인증 객체를 함께 정리합니다.
     *
     * 과거 PRIMARY였던 계정이 비활성화된 이후의 요청을 재현합니다.
     * 비활성 상태를 조회한 뒤 Session 및 SecurityContext가 모두 비어야 합니다.
     */
    @Test
    void 비활성_회원은_세션과_오래된_PRIMARY_인증을_함께_제거한다() throws Exception {
        setOldPrimaryAuthentication();

        // 로그인 상태로 저장된 회원을 비활성 계정으로 조회합니다.
        UserRepository users = mock(UserRepository.class);
        User suspended = mock(User.class);
        when(users.findById(1L)).thenReturn(Optional.of(suspended));
        when(suspended.getStatus()).thenReturn("SUSPENDED");
        MockHttpSession session = new MockHttpSession();
        session.setAttribute(SessionAuthenticationFilter.LOGIN_USER,
                new LoginUser(1L, "관리자", Set.of("PRIMARY")));
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setSession(session);

        // 필터가 이전 인증을 거부하고 로그인 속성을 제거해야 합니다.
        new SessionAuthenticationFilter(users,
                mock(GroupMembershipRepository.class),
                mock(UserActivityService.class))
                .doFilter(request, new MockHttpServletResponse(), mock(FilterChain.class));
        assertThat(session.getAttribute(SessionAuthenticationFilter.LOGIN_USER)).isNull();
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }

    /**
     * 기존 인증 객체가 있어도 DB 최신 역할로 바뀌어야 합니다.
     *
     * 이전 ROLE_PRIMARY에서 현재 MEMBER로 변경된 계정을 준비합니다.
     * 세션과 SecurityContext의 권한 모두 MEMBER가 되는지 검사합니다.
     */
    @Test
    void 활성_회원은_기존_PRIMARY_인증을_DB의_MEMBER로_교체한다() throws Exception {
        setOldPrimaryAuthentication();

        // 이전 세션은 PRIMARY지만 DB에는 MEMBER 멤버십만 있습니다.
        UserRepository users = mock(UserRepository.class);
        GroupMembershipRepository memberships = mock(GroupMembershipRepository.class);
        User user = mock(User.class);
        GroupMembership membership = mock(GroupMembership.class);
        when(users.findById(1L)).thenReturn(Optional.of(user));
        when(user.getId()).thenReturn(1L);
        when(user.getStatus()).thenReturn("ACTIVE");
        when(user.getGlobalRole()).thenReturn("USER");
        when(memberships.findByUserIdAndStatus(1L, "ACTIVE"))
                .thenReturn(Optional.of(membership));
        when(membership.getRole()).thenReturn("MEMBER");

        MockHttpSession session = new MockHttpSession();
        session.setAttribute(SessionAuthenticationFilter.LOGIN_USER,
                new LoginUser(1L, "관리자", Set.of("PRIMARY")));
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setSession(session);

        // 현재 멤버십을 기준으로 인증과 세션 역할을 동시에 교체합니다.
        new SessionAuthenticationFilter(users, memberships, mock(UserActivityService.class))
                .doFilter(request, new MockHttpServletResponse(), mock(FilterChain.class));
        LoginUser refreshed = (LoginUser) session.getAttribute(SessionAuthenticationFilter.LOGIN_USER);
        assertThat(refreshed.roles()).containsExactly("MEMBER");
        assertThat(SecurityContextHolder.getContext().getAuthentication().getAuthorities())
                .extracting("authority")
                .containsExactly("ROLE_MEMBER");
    }

    /**
     * 이전 요청에서 PRIMARY 인증 객체가 남아 있는 상태를 재현합니다.
     *
     * HttpSession에 대한 변경 없이 SecurityContext만 직접 구성합니다.
     * 이후 테스트별로 세션·DB 상태를 달리 설정하여 안전성을 검증합니다.
     */
    private void setOldPrimaryAuthentication() {
        // 존재하지 않거나 권한이 변경된 계정의 오래된 인증을 준비합니다.
        SecurityContextHolder.getContext().setAuthentication(
                UsernamePasswordAuthenticationToken.authenticated(
                        new LoginUser(1L, "관리자", Set.of("PRIMARY")),
                        "N/A",
                        List.of(new SimpleGrantedAuthority("ROLE_PRIMARY"))
                )
        );
    }
}
