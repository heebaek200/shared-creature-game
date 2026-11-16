package com.team.sharedcreature.avatar.support;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.TestComponent;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** DB 접속 없이 서버의 세션 재발급을 재현해 테스트 클라이언트의 인증 유지 동작을 검증합니다. */
class SessionCookieClientTest {

    @Test
    void 서버가_세션을_재발급하면_다음_요청에는_새_Cookie를_보낸다() throws Exception {
        var mvc = MockMvcBuilders.standaloneSetup(new CookieController()).build();
        var browser = new SessionCookieClient(mvc, new Cookie("SESSION", encode("first-session")));

        browser.perform(get("/rotate")).andExpect(status().isOk());
        browser.perform(get("/check"))
                .andExpect(status().isOk()).andExpect(content().string(encode("next-session")));

        assertThat(browser.sessionIds()).containsExactlyInAnyOrder("first-session", "next-session");
    }

    @Test
    void 세션_삭제_Cookie를_받으면_다음_요청에_이전_Cookie를_다시_보내지_않는다() throws Exception {
        var mvc = MockMvcBuilders.standaloneSetup(new CookieController()).build();
        var browser = new SessionCookieClient(mvc, new Cookie("SESSION", encode("first-session")));

        browser.perform(get("/expire")).andExpect(status().isOk());
        browser.perform(get("/check"))
                .andExpect(status().isOk()).andExpect(content().string("absent"));
    }

    private static String encode(String value) {
        return Base64.getEncoder().encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }

    /** 테스트 응답으로 Cookie 재발급과 만료를 재현하는 전용 Controller입니다. */
    @TestComponent
    @RestController
    static class CookieController {

        @GetMapping("/rotate")
        void rotate(HttpServletResponse response) {
            response.addHeader("Set-Cookie", "SESSION=" + encode("next-session") + "; Path=/; HttpOnly");
        }

        @GetMapping("/expire")
        void expire(HttpServletResponse response) {
            response.addHeader("Set-Cookie", "SESSION=; Path=/; Max-Age=0; HttpOnly");
        }

        @GetMapping("/check")
        String check(@CookieValue(name = "SESSION", defaultValue = "absent") String value) {
            return value;
        }
    }
}
