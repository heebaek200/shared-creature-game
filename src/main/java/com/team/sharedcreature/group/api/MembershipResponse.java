package com.team.sharedcreature.group.api;

import java.time.Instant;

/** 그룹 가입 결과의 활성 멤버십 정보를 제공합니다. */
public record MembershipResponse(
        Long membershipId,
        Long groupId,
        String role,
        Instant joinedAt,
        String status
) {
}
