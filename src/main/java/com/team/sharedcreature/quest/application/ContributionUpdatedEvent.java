package com.team.sharedcreature.quest.application;

import java.time.Instant;

/** Commit 이후 실시간 계층에 전달할 기여도 변경 사실입니다. */
public record ContributionUpdatedEvent(
        Long groupId,
        Long userId,
        long contributionDelta,
        long contributionScore,
        long creatureExperienceDelta,
        long groupScore,
        Instant occurredAt
) {
}
