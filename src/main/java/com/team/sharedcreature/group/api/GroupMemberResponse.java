package com.team.sharedcreature.group.api;

import java.time.Instant;

import com.team.sharedcreature.group.domain.GroupMembershipStatus;
import com.team.sharedcreature.group.domain.GroupRole;

/**
 * 그룹에 소속된 활성 구성원 한 명의 조회 결과입니다.
 * <p>
 * 역할 관리와 비활동 강퇴 화면에서 동일하게 사용합니다.
 * 마지막 활동 시각은 인증 공통 기능에서 저장한 값을 반환합니다.
 */
public record GroupMemberResponse(
        Long userId,
        String nickname,
        Long membershipId,
        String role,
        String status,
        Instant joinedAt,
        Instant lastActiveAt
) {

    /**
     * JPQL과 서비스에서 전달한 역할·멤버십 상태를 API 문자열로 변환합니다.
     */
    public GroupMemberResponse(
            Long userId,
            String nickname,
            Long membershipId,
            GroupRole role,
            GroupMembershipStatus status,
            Instant joinedAt,
            Instant lastActiveAt
    ) {
        // 기존 응답 필드와 상태 문자열을 유지합니다.
        this(
                userId,
                nickname,
                membershipId,
                role.name(),
                status.name(),
                joinedAt,
                lastActiveAt
        );
    }
}
