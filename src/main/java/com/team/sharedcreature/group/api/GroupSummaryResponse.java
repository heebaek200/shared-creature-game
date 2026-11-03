package com.team.sharedcreature.group.api;

/** 공개 그룹 선택 목록의 가입 가능 상태를 제공합니다. */
public record GroupSummaryResponse(
        Long groupId,
        String name,
        String description,
        int currentMemberCount,
        int capacity,
        int remainingCapacity,
        boolean joinable,
        String joinBlockedReason
) {
}
