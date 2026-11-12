package com.team.sharedcreature._core.realtime;

import java.time.Instant;
import java.util.UUID;

/** 그룹 토픽으로 전달하는 공통 STOMP 이벤트 Envelope입니다. */
public record GroupLiveEvent(
        UUID eventId,
        UUID streamInstanceId,
        long sequence,
        String eventType,
        Long groupId,
        Instant occurredAt,
        Object payload
) {
}
