package com.team.sharedcreature.season.application;

import java.time.Instant;

/** Commit 이후 실시간 계층에 전달할 크리처 변경 사실입니다. */
public record CreatureUpdatedEvent(
        Long groupId,
        Long groupCreatureId,
        String stage,
        long experience,
        Instant occurredAt
) {
}
