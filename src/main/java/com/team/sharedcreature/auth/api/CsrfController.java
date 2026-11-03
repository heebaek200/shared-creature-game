package com.team.sharedcreature.auth.api;

import com.team.sharedcreature._core.api.ApiResponse;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 브라우저 세션에 결속된 CSRF Token을 제공합니다.
 */
@RestController
@RequestMapping("/api/v1/auth")
public class CsrfController {

    /**
     * 현재 세션의 CSRF Header 이름과 Token을 반환합니다.
     *
     * @param token Spring Security가 생성한 Token
     * @return CSRF 응답
     */
    @GetMapping("/csrf")
    public ApiResponse<CsrfResponse> getCsrfToken(CsrfToken token) {
        return ApiResponse.success(new CsrfResponse(token.getHeaderName(), token.getToken()));
    }

    /** CSRF Token 응답입니다. */
    public record CsrfResponse(String headerName, String token) {
    }
}
