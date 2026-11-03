package com.team.sharedcreature.group.api;

import java.time.Instant;

/**
 * 생성된 그룹과 최초 주 관리자·초대 링크를 반환합니다.
 *
 * @param group 생성된 그룹 상세
 * @param initialInvitationUrl 최초 한 번만 노출하는 초대 URL
 */
public record CreateGroupResponse(GroupDetailResponse group, String initialInvitationUrl) {

    /** 생성된 그룹의 핵심 정보를 제공합니다. */
    public record GroupDetailResponse(
            GroupSummaryResponse group,
            GroupMemberResponse primaryAdmin,
            MembershipResponse myMembership
    ) {
    }

    /** 공개 그룹 목록과 상세에서 공통으로 사용하는 요약입니다. */
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

    /** 그룹 회원의 표시 정보입니다. */
    public record GroupMemberResponse(Long userId, String nickname, String role, Instant joinedAt) {
    }

    /** 현재 사용자의 활성 멤버십 정보입니다. */
    public record MembershipResponse(Long membershipId, Long groupId, String role, Instant joinedAt, String status) {
    }
}
