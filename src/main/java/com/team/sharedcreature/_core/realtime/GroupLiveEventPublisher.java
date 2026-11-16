package com.team.sharedcreature._core.realtime;

import java.time.Instant;

/** Commit 이후 확정된 그룹 변경을 STOMP 토픽에 전달하는 경계입니다. */
public interface GroupLiveEventPublisher {

    /** 지정 그룹에 계약된 이벤트를 발행합니다. */
    void publish(Long groupId, String eventType, Instant occurredAt, Object payload);
}
