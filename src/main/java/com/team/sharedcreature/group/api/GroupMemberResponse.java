package com.team.sharedcreature.group.api;

import java.time.Instant;

/**
 * 그룹에 소속된 활성 구성원 한 명의 조회 결과입니다.
 *
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
) {}
