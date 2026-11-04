package com.team.sharedcreature._core.security;

import com.team.sharedcreature._core.api.ErrorResponse;
import com.team.sharedcreature._core.api.ErrorResponseFactory;
import com.team.sharedcreature._core.exception.ErrorCode;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.util.List;

/**
 * Spring Security 단계의 실패도 공통 REST 오류 형식으로 기록합니다.
 */
@Component
public class SecurityErrorWriter {

    private final ObjectMapper objectMapper;
    private final ErrorResponseFactory responseFactory;

    /**
     * JSON 직렬화기와 공통 오류 응답 생성기를 주입받습니다.
     *
     * @param objectMapper 애플리케이션 JSON 직렬화기
     * @param responseFactory 오류 Envelope 생성기
     */
    public SecurityErrorWriter(ObjectMapper objectMapper, ErrorResponseFactory responseFactory) {
        this.objectMapper = objectMapper;
        this.responseFactory = responseFactory;
    }

    /**
     * 인증·인가 오류 JSON을 응답에 기록합니다.
     *
     * @param request 현재 요청
     * @param response 현재 응답
     * @param errorCode API 오류 코드
     * @throws IOException 응답 기록 실패
     */
    public void write(
            HttpServletRequest request,
            HttpServletResponse response,
            ErrorCode errorCode
    ) throws IOException {
        response.setStatus(errorCode.status().value());
        response.setCharacterEncoding("UTF-8");
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        Object requestId = request.getAttribute("requestId");
        String traceId = requestId == null ? "unavailable" : requestId.toString();
        ErrorResponse body = responseFactory.create(errorCode, null, List.of(), traceId);
        objectMapper.writeValue(response.getWriter(), body);
    }
}
