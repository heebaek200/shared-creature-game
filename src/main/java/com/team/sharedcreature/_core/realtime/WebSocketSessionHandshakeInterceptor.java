package com.team.sharedcreature._core.realtime;

import com.team.sharedcreature._core.security.LoginUser;
import com.team.sharedcreature._core.security.SessionAuthenticationFilter;
import jakarta.servlet.http.HttpSession;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.http.server.ServletServerHttpRequest;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.server.HandshakeInterceptor;

import java.util.Enumeration;
import java.util.Map;

/** HTTP Session의 인증·CSRF 정보를 WebSocket Session 속성으로 복사합니다. */
@Component
public class WebSocketSessionHandshakeInterceptor implements HandshakeInterceptor {

    public static final String LOGIN_USER_ATTRIBUTE = "websocket.loginUser";
    public static final String HTTP_SESSION_ID_ATTRIBUTE = "websocket.httpSessionId";
    public static final String CSRF_TOKEN_ATTRIBUTE = "websocket.csrfToken";

    /** 인증 세션과 CSRF Token을 이후 STOMP CONNECT 검증에 사용할 수 있게 보관합니다. */
    @Override
    public boolean beforeHandshake(
            ServerHttpRequest request,
            ServerHttpResponse response,
            WebSocketHandler wsHandler,
            Map<String, Object> attributes
    ) {
        if (!(request instanceof ServletServerHttpRequest servletRequest)) {
            return false;
        }
        HttpSession session = servletRequest.getServletRequest().getSession(false);
        if (session == null) {
            return false;
        }
        Object loginUser = session.getAttribute(SessionAuthenticationFilter.LOGIN_USER);
        if (!(loginUser instanceof LoginUser)) {
            return false;
        }
        attributes.put(LOGIN_USER_ATTRIBUTE, loginUser);
        attributes.put(HTTP_SESSION_ID_ATTRIBUTE, session.getId());
        findCsrfToken(session).ifPresent(token -> attributes.put(CSRF_TOKEN_ATTRIBUTE, token));
        return true;
    }

    @Override
    public void afterHandshake(
            ServerHttpRequest request,
            ServerHttpResponse response,
            WebSocketHandler wsHandler,
            Exception exception
    ) {
        // Handshake 이후 추가 자원은 보관하지 않습니다.
    }

    private java.util.Optional<String> findCsrfToken(HttpSession session) {
        Enumeration<String> names = session.getAttributeNames();
        while (names.hasMoreElements()) {
            Object value = session.getAttribute(names.nextElement());
            if (value instanceof CsrfToken csrfToken) {
                return java.util.Optional.of(csrfToken.getToken());
            }
        }
        return java.util.Optional.empty();
    }
}
