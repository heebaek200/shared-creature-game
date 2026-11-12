package com.team.sharedcreature.avatar.support;

import jakarta.servlet.http.Cookie;
import org.springframework.mock.web.MockCookie;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.LinkedHashSet;
import java.util.Set;

/** 서버가 재발급한 세션 Cookie를 다음 요청에 반영하는 이슈 20 테스트 클라이언트입니다. */
public final class SessionCookieClient {

    private final MockMvc mvc;
    private final String cookieName;
    private final Set<String> sessionIds = new LinkedHashSet<>();
    private Cookie currentCookie;

    /**
     * 로그인 세션 Cookie로 테스트 브라우저를 시작합니다.
     *
     * @param mvc 실제 HTTP 테스트 실행기
     * @param initialCookie Spring Session 형식의 최초 로그인 Cookie
     */
    public SessionCookieClient(MockMvc mvc, Cookie initialCookie) {
        this.mvc = mvc;
        this.cookieName = initialCookie.getName();
        this.currentCookie = initialCookie;
        rememberSession(initialCookie);
    }

    /**
     * 최신 Cookie로 요청하고 응답의 Set-Cookie를 다음 요청에 반영합니다.
     *
     * @param request 실행할 HTTP 요청
     * @return 응답 검증을 이어갈 결과
     * @throws Exception HTTP 테스트 실행 중 오류가 발생한 경우
     */
    public ResultActions perform(MockHttpServletRequestBuilder request) throws Exception {
        if (currentCookie != null) {
            request.cookie(currentCookie);
        }
        return mvc.perform(request).andDo(result -> acceptCookies(result.getResponse()));
    }

    /** 테스트 종료 시 최초 세션과 교체된 세션을 모두 정리할 식별자를 반환합니다. */
    public Set<String> sessionIds() {
        return Set.copyOf(sessionIds);
    }

    /** 브라우저처럼 같은 이름의 Cookie를 교체하고 만료 응답도 반영합니다. */
    private void acceptCookies(MockHttpServletResponse response) {
        for (String header : response.getHeaders("Set-Cookie")) {
            MockCookie cookie = MockCookie.parse(header);
            if (cookieName.equals(cookie.getName())) {
                if (cookie.getMaxAge() == 0 || cookie.getValue().isEmpty()) {
                    currentCookie = null;
                } else {
                    currentCookie = cookie;
                    rememberSession(cookie);
                }
            }
        }
    }

    /** 기존 Spring Session 기본 인코딩을 해석하며 세션 값은 로그에 출력하지 않습니다. */
    private void rememberSession(Cookie cookie) {
        sessionIds.add(new String(Base64.getDecoder().decode(cookie.getValue()), StandardCharsets.UTF_8));
    }
}
