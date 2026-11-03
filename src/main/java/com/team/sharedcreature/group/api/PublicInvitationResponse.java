package com.team.sharedcreature.group.api;

/** 공개 초대 화면에 노출할 그룹과 수락 가능 상태입니다. */
public record PublicInvitationResponse(
        Long groupId,
        String groupName,
        int currentMemberCount,
        int capacity,
        String status,
        boolean acceptable
) {
}
