package com.team.sharedcreature._core.realtime;

import org.springframework.messaging.Message;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.messaging.StompSubProtocolErrorHandler;

/** STOMP 접근 예외를 클라이언트가 분기할 수 있는 계약 오류 Header로 변환합니다. */
@Component
public class GroupStompErrorHandler extends StompSubProtocolErrorHandler {

    /** 계약 예외의 code를 ERROR Frame의 message Header에 그대로 기록합니다. */
    @Override
    public Message<byte[]> handleClientMessageProcessingError(Message<byte[]> clientMessage, Throwable exception) {
        StompAccessException accessException = findAccessException(exception);
        if (accessException == null) {
            return super.handleClientMessageProcessingError(clientMessage, exception);
        }
        StompHeaderAccessor accessor = StompHeaderAccessor.create(StompCommand.ERROR);
        accessor.setMessage(accessException.code());
        accessor.setLeaveMutable(true);
        return MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders());
    }

    private StompAccessException findAccessException(Throwable exception) {
        Throwable current = exception;
        while (current != null) {
            if (current instanceof StompAccessException accessException) {
                return accessException;
            }
            current = current.getCause();
        }
        return null;
    }
}
