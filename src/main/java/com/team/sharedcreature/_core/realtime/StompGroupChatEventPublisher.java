package com.team.sharedcreature._core.realtime;

import com.team.sharedcreature.group.api.GroupChatMessageResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;

/** 채팅 이벤트를 별도 STOMP 토픽에 Best Effort로 발행합니다. */
@Slf4j
@Component
public class StompGroupChatEventPublisher implements GroupChatEventPublisher {

    private final SimpMessagingTemplate messagingTemplate;

    /** STOMP 토픽 전송기를 주입받습니다. */
    public StompGroupChatEventPublisher(SimpMessagingTemplate messagingTemplate) {
        this.messagingTemplate = messagingTemplate;
    }

    /** 발행 실패를 저장 결과와 분리하고 운영 로그에 남깁니다. */
    @Override
    public void publish(GroupChatMessageResponse message) {
        try {
            messagingTemplate.convertAndSend(
                    "/topic/groups/" + message.groupId() + "/chat", message);
        } catch (RuntimeException exception) {
            log.error("채팅 STOMP 발행 실패. messageId={}, groupId={}",
                    message.messageId(), message.groupId(), exception);
        }
    }
}
