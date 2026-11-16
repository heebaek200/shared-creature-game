package com.team.sharedcreature._core.realtime;

import org.junit.jupiter.api.Test;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessagingException;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;

import static org.assertj.core.api.Assertions.assertThat;

class GroupStompErrorHandlerTest {

    @Test
    void 중첩된_계약_예외의_code를_ERROR_message_Header로_전달한다() {
        GroupStompErrorHandler handler = new GroupStompErrorHandler();
        Throwable wrapped = new MessagingException("delivery failed", new StompAccessException("CSRF_INVALID"));

        Message<byte[]> error = handler.handleClientMessageProcessingError(null, wrapped);
        StompHeaderAccessor accessor = StompHeaderAccessor.wrap(error);

        assertThat(accessor.getMessage()).isEqualTo("CSRF_INVALID");
        assertThat(error.getPayload()).isEmpty();
    }
}
