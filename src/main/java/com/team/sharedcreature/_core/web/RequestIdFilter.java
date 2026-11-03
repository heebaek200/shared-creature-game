package com.team.sharedcreature._core.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * 요청과 응답을 연결하는 X-Request-Id를 검증하거나 생성합니다.
 */
@Component
public class RequestIdFilter extends OncePerRequestFilter {

    private static final Pattern ALLOWED = Pattern.compile("[A-Za-z0-9_-]{1,64}");

    /** 요청 ID를 request attribute와 응답 Header에 동일하게 기록합니다. */
    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain
    ) throws ServletException, IOException {
        String supplied = request.getHeader("X-Request-Id");
        String requestId = supplied != null && ALLOWED.matcher(supplied).matches()
                ? supplied
                : UUID.randomUUID().toString();
        request.setAttribute("requestId", requestId);
        response.setHeader("X-Request-Id", requestId);
        filterChain.doFilter(request, response);
    }
}
