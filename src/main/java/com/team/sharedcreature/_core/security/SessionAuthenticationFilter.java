package com.team.sharedcreature._core.security;

import com.team.sharedcreature.auth.application.UserActivityService;
import com.team.sharedcreature.auth.domain.User;
import com.team.sharedcreature.auth.infrastructure.UserRepository;
import com.team.sharedcreature.group.infrastructure.GroupMembershipRepository;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.TransactionException;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.HashSet;
import java.util.Set;

/**
 * Spring Session에 저장한 LoginUser를 Spring Security 인증으로 복원합니다.
 */
@Slf4j
@Component
public class SessionAuthenticationFilter extends OncePerRequestFilter {

    public static final String LOGIN_USER = "AUTHENTICATED_USER";
    public static final String SESSION_EXPIRED = "SESSION_EXPIRED";

    private final UserRepository userRepository;
    private final GroupMembershipRepository membershipRepository;
    private UserActivityService userActivityService;

    /** 계정 상태와 최신 활성 멤버십 역할을 조회할 저장소를 주입받습니다. */
    public SessionAuthenticationFilter(
            UserRepository userRepository,
            GroupMembershipRepository membershipRepository,
            UserActivityService userActivityService
    ) {
        this.userRepository = userRepository;
        this.membershipRepository = membershipRepository;
        this.userActivityService = userActivityService;
    }

    /**
     * 세션 사용자의 계정 상태와 최신 그룹 역할을 확인하여 인증을 복원합니다.
     *
     * 기존 FilterChain을 정상적으로 통과한 2xx 인증 API 요청만 활동으로 인정합니다.
     * 활동 기록은 비즈니스 응답 처리가 끝난 뒤 별도 Service에서 수행하며,
     * 기록용 DB 접근 실패가 이미 성공한 요청을 실패로 바꾸지 않도록 격리합니다.
     */
    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain
    ) throws ServletException, IOException {

        // 만료된 세션 ID가 전달되면 기존 세션 만료 표시를 유지합니다.
        if (request.getRequestedSessionId() != null
                && !request.isRequestedSessionIdValid()) {
            request.setAttribute(SESSION_EXPIRED, true);
        }

        Object attribute = request.getSession(false) == null
                ? null
                : request.getSession(false).getAttribute(LOGIN_USER);

        // 매 요청에서 오래된 인증 객체를 제거합니다.
        // 유효한 LOGIN_USER가 확인된 경우에만 아래 로직에서 인증을 다시 생성합니다.
        SecurityContextHolder.clearContext();

        Long authenticatedUserId = null;

        // 기존 세션의 사용자 상태와 최신 역할을 다시 조회합니다.
        if (attribute instanceof LoginUser sessionUser) {

            User user = userRepository.findById(sessionUser.id()).orElse(null);

            if (user == null || !"ACTIVE".equals(user.getStatus())) {
                request.getSession(false).removeAttribute(LOGIN_USER);
                filterChain.doFilter(request, response);
                return;
            }

            var currentRoles = new HashSet<String>();

            // 전역 관리자 권한을 유지합니다.
            if ("SUPER_ADMIN".equals(user.getGlobalRole())) {
                currentRoles.add("SUPER_ADMIN");
            }

            membershipRepository.findByUserIdAndStatus(user.getId(), "ACTIVE")
                    .ifPresent(membership -> currentRoles.add(membership.getRole()));

            Set<String> roles = Set.copyOf(currentRoles);
            LoginUser loginUser = new LoginUser(
                    user.getId(),
                    sessionUser.nickname(),
                    roles
            );

            // 최신 역할이 이전 세션과 다르면 세션 정보를 동기화합니다.
            if (!loginUser.equals(sessionUser)) {
                request.getSession(false).setAttribute(LOGIN_USER, loginUser);
            }

            var authorities = roles.stream()
                    .map(role -> new SimpleGrantedAuthority("ROLE_" + role))
                    .toList();

            var authentication = UsernamePasswordAuthenticationToken.authenticated(
                    loginUser,
                    "N/A",
                    authorities
            );

            SecurityContextHolder.getContext().setAuthentication(authentication);
            authenticatedUserId = user.getId();
        }

        // Controller 및 후속 Filter가 요청을 처리하도록 합니다.
        filterChain.doFilter(request, response);

        // API 요청이 2xx로 완료되고 인증 세션이 유지된 경우에만 기록합니다.
        if (authenticatedUserId != null
                && request.getRequestURI().startsWith("/api/v1/")
                && response.getStatus() >= 200
                && response.getStatus() < 300
                && !request.isAsyncStarted()
                && request.getSession(false) != null
                && request.getSession(false).getAttribute(LOGIN_USER)
                instanceof LoginUser currentUser
                && authenticatedUserId.equals(currentUser.id())) {

            try {
                // 인증 활동 기록은 별도 트랜잭션에 위임합니다.
                userActivityService.recordSuccessfulRequest(authenticatedUserId);
            } catch (DataAccessException | TransactionException exception) {
                // 활동 기록 장애가 정상 완료된 업무 요청에 영향을 주지 않도록 합니다.
                log.warn("인증 활동 시각 갱신 실패: userId={}",
                        authenticatedUserId, exception);
            }
        }
    }

}
