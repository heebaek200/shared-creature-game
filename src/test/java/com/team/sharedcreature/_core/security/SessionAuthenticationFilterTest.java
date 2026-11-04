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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.CannotCreateTransactionException;

import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

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

    /**
     * 실패한 인증 API 요청에서는 활동 시각을 기록하지 않는지 검증합니다.
     *
     * HTTP 403과 500 응답을 각각 모의하여 후속 FilterChain의 처리 결과를 확인합니다.
     * 인증된 세션이라도 정상 응답이 아니라면 활동 기록 Service를 호출하지 않아야 합니다.
     *
     * @param status 모의할 HTTP 실패 상태 코드
     */
    @ParameterizedTest
    @ValueSource(ints = {403, 500})
    void 실패한_인증_API_요청은_활동을_기록하지_않는다(int status) throws Exception {
        // 정상 로그인 세션에서 실패 응답을 받은 상황을 재현합니다.
        UserActivityService activityService = mock(UserActivityService.class);

        MockHttpServletResponse response =
                performAuthenticatedRequest(status, activityService);

        // 응답 상태를 유지하면서 활동 기록을 수행하지 않았는지 확인합니다.
        assertThat(response.getStatus()).isEqualTo(status);
        verify(activityService, never()).recordSuccessfulRequest(anyLong());
    }

    /**
     * 활동 기록 과정에서 Spring 트랜잭션 예외가 발생해도 응답이 유지되는지 검증합니다.
     *
     * 실제 업무 API는 이미 성공적으로 처리된 상태를 가정합니다.
     * 후속 활동 기록 트랜잭션 생성에 실패하더라도 HTTP 200 응답이 유지되어야 합니다.
     */
    @Test
    void 활동_기록_트랜잭션_예외가_발생해도_정상_응답을_유지한다() throws Exception {
        UserActivityService activityService = mock(UserActivityService.class);

        // Spring 트랜잭션 시작 실패를 모의합니다.
        when(activityService.recordSuccessfulRequest(1L))
                .thenThrow(new CannotCreateTransactionException("DB 연결 실패"));

        MockHttpServletResponse response =
                performAuthenticatedRequest(200, activityService);

        // 기록 실패가 기존 API의 성공 응답을 변경하지 않는지 확인합니다.
        assertThat(response.getStatus()).isEqualTo(200);
        verify(activityService).recordSuccessfulRequest(1L);
    }

    /**
     * 유효한 세션으로 인증 API를 호출한 상황을 생성합니다.
     *
     * 실제 사용자 저장소와 활동 기록 Service는 Mockito 객체로 대체합니다.
     * 응답 상태는 FilterChain에서 지정하고 SessionAuthenticationFilter를 통과시킵니다.
     *
     * @param status 후속 처리에서 반환할 HTTP 상태
     * @param activityService 검증 대상 활동 기록 Service
     * @return Filter 실행이 끝난 HTTP 응답
     */
    private MockHttpServletResponse performAuthenticatedRequest(
            int status,
            UserActivityService activityService
    ) throws Exception {
        UserRepository userRepository = mock(UserRepository.class);
        GroupMembershipRepository membershipRepository =
                mock(GroupMembershipRepository.class);

        // ACTIVE 사용자가 정상 인증되는 상황을 준비합니다.
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

        // Filter가 활동 기록 대상으로 인식할 API 경로를 사용합니다.
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRequestURI("/api/v1/me");
        request.setSession(session);

        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = (req, res) ->
                ((HttpServletResponse) res).setStatus(status);

        // 실제 Filter의 인증 복원과 응답 후처리를 실행합니다.
        new SessionAuthenticationFilter(
                userRepository,
                membershipRepository,
                activityService
        ).doFilter(request, response, chain);

        return response;
    }
}
