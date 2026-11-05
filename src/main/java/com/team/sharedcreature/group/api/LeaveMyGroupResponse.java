package com.team.sharedcreature.group.api;

import java.time.Instant;

public record LeaveMyGroupResponse (
        Long groupId,
        Long userId,
        Instant endedAt,
        String exitReason,
        Instant restrictionUtil,
        boolean groupDeactivated
) {}
