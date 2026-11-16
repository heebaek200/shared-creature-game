package com.team.sharedcreature._core.realtime;

import lombok.extern.slf4j.Slf4j;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.WebSocketHandlerDecorator;
import org.springframework.web.socket.handler.WebSocketHandlerDecoratorFactory;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** 권한이 회수된 사용자의 실제 WebSocket 연결을 즉시 종료할 수 있도록 활성 연결을 관리합니다. */
@Slf4j
@Component
public class ActiveWebSocketConnectionRegistry implements WebSocketHandlerDecoratorFactory {

    private static final CloseStatus ACCESS_REVOKED =
            new CloseStatus(CloseStatus.POLICY_VIOLATION.getCode(), "ACCESS_REVOKED");

    private final Map<String, WebSocketSession> sessions = new ConcurrentHashMap<>();

    /** 연결 수명 주기에 맞춰 Session을 등록·제거하는 Handler를 생성합니다. */
    @Override
    public WebSocketHandler decorate(WebSocketHandler handler) {
        return new WebSocketHandlerDecorator(handler) {
            @Override
            public void afterConnectionEstablished(WebSocketSession session) throws Exception {
                sessions.put(session.getId(), session);
                try {
                    super.afterConnectionEstablished(session);
                } catch (Exception exception) {
                    sessions.remove(session.getId(), session);
                    throw exception;
                }
            }

            @Override
            public void afterConnectionClosed(WebSocketSession session, CloseStatus closeStatus)
                    throws Exception {
                sessions.remove(session.getId(), session);
                super.afterConnectionClosed(session, closeStatus);
            }
        };
    }

    /** 지정한 활성 연결을 권한 회수 상태로 종료합니다. 이미 닫힌 연결은 무시합니다. */
    public void close(String webSocketSessionId) {
        WebSocketSession session = sessions.remove(webSocketSessionId);
        if (session == null || !session.isOpen()) {
            return;
        }
        try {
            session.close(ACCESS_REVOKED);
        } catch (IOException exception) {
            log.warn("권한이 회수된 WebSocket 연결 종료에 실패했습니다. sessionId={}",
                    webSocketSessionId, exception);
        }
    }
}
