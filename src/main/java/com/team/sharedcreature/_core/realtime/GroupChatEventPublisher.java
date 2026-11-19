package com.team.sharedcreature._core.realtime;

import com.team.sharedcreature.group.api.GroupChatMessageResponse;

/** 확정된 채팅 메시지를 상태 Stream Cursor와 분리해 전달합니다. */
public interface GroupChatEventPublisher {

    void publish(GroupChatMessageResponse message);
}
