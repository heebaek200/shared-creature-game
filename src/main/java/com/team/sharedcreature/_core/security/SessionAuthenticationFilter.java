package com.team.sharedcreature._core.security;

import com.team.sharedcreature.auth.domain.User;
import com.team.sharedcreature.auth.infrastructure.UserRepository;
import com.team.sharedcreature.group.infrastructure.GroupMembershipRepository;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.HashSet;
import java.util.Set;

/**
 * Spring Session에 저장한 LoginUser를 Spring Security 인증으로 복원합니다.
 */
@Component
public class SessionAuthenticationFilter extends OncePerRequestFilter {

    public static final String LOGIN_USER = "AUTHENTICATED_USER";

    private final UserRepository userRepository;
    private final GroupMembershipRepository membershipRepository;

    /** 계정 상태와 최신 활성 멤버십 역할을 조회할 저장소를 주입받습니다. */
    public SessionAuthenticationFilter(
            UserRepository userRepository,
            GroupMembershipRepository membershipRepository
    ) {
        this.userRepository = userRepository;
        this.membershipRepository = membershipRepository;
    }

    /** 세션 사용자가 있으면 현재 요청의 SecurityContext를 구성합니다. */
    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain
    ) throws ServletException, IOException {
        Object attribute = request.getSession(false) == null
                ? null
                : request.getSession(false).getAttribute(LOGIN_USER);
        if (attribute instanceof LoginUser sessionUser
                && SecurityContextHolder.getContext().getAuthentication() == null) {
            User user = userRepository.findById(sessionUser.id()).orElse(null);
            if (user == null || !"ACTIVE".equals(user.getStatus())) {
                request.getSession(false).removeAttribute(LOGIN_USER);
                filterChain.doFilter(request, response);
                return;
            }

            var currentRoles = new HashSet<String>();
            if ("SUPER_ADMIN".equals(user.getGlobalRole())) {
                currentRoles.add("SUPER_ADMIN");
            }
            membershipRepository.findByUserIdAndStatus(user.getId(), "ACTIVE")
                    .ifPresent(membership -> currentRoles.add(membership.getRole()));
            Set<String> roles = Set.copyOf(currentRoles);
            LoginUser loginUser = new LoginUser(user.getId(), sessionUser.nickname(), roles);
            if (!loginUser.equals(sessionUser)) {
                request.getSession(false).setAttribute(LOGIN_USER, loginUser);
            }
            var authorities = roles.stream()
                    .map(role -> new SimpleGrantedAuthority("ROLE_" + role))
                    .toList();
            var authentication = UsernamePasswordAuthenticationToken.authenticated(loginUser, "N/A", authorities);
            SecurityContextHolder.getContext().setAuthentication(authentication);
        }
        filterChain.doFilter(request, response);
    }
}
