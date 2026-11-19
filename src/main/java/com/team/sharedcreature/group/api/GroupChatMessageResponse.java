package com.team.sharedcreature.group.api;

import java.time.Instant;

/** REST 성공 응답과 채팅 STOMP 이벤트가 공유하는 확정 메시지입니다. */
public record GroupChatMessageResponse(
        String messageId,
        Long groupId,
        Long senderUserId,
        String senderNickname,
        String content,
        Instant sentAt
) {
}
