package com.team.sharedcreature.group.api;

import java.time.Instant;

/**
 * 현재 소속 그룹의 상세 정보와 사용자 멤버십을 반환합니다.
 *
 * 기존 그룹 요약 DTO를 재사용하고 관리자와 멤버십 정보를 추가합니다.
 * 응답 구조는 getMyGroup API 계약을 따릅니다.
 */
public record MyGroupResponse(
        GroupSummaryResponse group,
        PrimaryAdminResponse primaryAdmin,
        MyMembershipResponse myMembership
) {

    /** 활성 그룹의 주 관리자 표시 정보입니다. */
    public record PrimaryAdminResponse(
            Long userId,
            String nickname
    ) {}

    /** 요청 사용자의 활성 그룹 멤버십입니다. */
    public record MyMembershipResponse(
            Long membershipId,
            Long groupId,
            String role,
            String status,
            Instant joinedAt
    ) {}

}