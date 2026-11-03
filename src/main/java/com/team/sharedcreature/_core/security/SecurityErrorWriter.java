package com.team.sharedcreature._core.security;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.time.Clock;
import java.time.Instant;

/**
 * Spring Security 단계의 실패도 공통 REST 오류 형식으로 기록합니다.
 */
@Component
public class SecurityErrorWriter {

    private final Clock clock;

    /**
     * 오류 발생 시각을 제공하는 Clock을 주입받습니다.
     *
     * @param clock 공통 UTC Clock
     */
    public SecurityErrorWriter(Clock clock) {
        this.clock = clock;
    }

    /**
     * 인증·인가 오류 JSON을 응답에 기록합니다.
     *
     * @param request 현재 요청
     * @param response 현재 응답
     * @param status HTTP 상태
     * @param code 오류 코드
     * @param message 안전한 오류 메시지
     * @throws IOException 응답 기록 실패
     */
    public void write(
            HttpServletRequest request,
            HttpServletResponse response,
            int status,
            String code,
            String message
    ) throws IOException {
        response.setStatus(status);
        response.setCharacterEncoding("UTF-8");
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        String traceId = String.valueOf(request.getAttribute("requestId"));
        response.getWriter().printf(
                "{\"code\":\"%s\",\"message\":\"%s\",\"fieldErrors\":[],\"traceId\":\"%s\",\"timestamp\":\"%s\"}",
                code,
                message,
                traceId,
                Instant.now(clock)
        );
    }
}
