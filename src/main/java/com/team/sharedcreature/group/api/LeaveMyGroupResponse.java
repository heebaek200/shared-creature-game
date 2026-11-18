package com.team.sharedcreature.group.api;

import com.team.sharedcreature.group.domain.GroupMembershipExitReason;

import java.time.Instant;

public record LeaveMyGroupResponse (
        Long groupId,
        Long userId,
        Instant endedAt,
        GroupMembershipExitReason exitReason,
        Instant restrictionUntil,
        boolean groupDeactivated
) {}
