package com.team.sharedcreature._core.security;

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

/**
 * Spring Session에 저장한 LoginUser를 Spring Security 인증으로 복원합니다.
 */
@Component
public class SessionAuthenticationFilter extends OncePerRequestFilter {

    public static final String LOGIN_USER = "AUTHENTICATED_USER";

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
        if (attribute instanceof LoginUser loginUser
                && SecurityContextHolder.getContext().getAuthentication() == null) {
            var authorities = loginUser.roles().stream()
                    .map(role -> new SimpleGrantedAuthority("ROLE_" + role))
                    .toList();
            var authentication = UsernamePasswordAuthenticationToken.authenticated(loginUser, "N/A", authorities);
            SecurityContextHolder.getContext().setAuthentication(authentication);
        }
        filterChain.doFilter(request, response);
    }
}
