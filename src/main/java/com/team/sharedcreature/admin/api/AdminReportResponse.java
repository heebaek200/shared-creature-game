package com.team.sharedcreature.admin.api;

import java.time.Instant;

/** 전체 관리자가 조회하는 신고 상세와 처리 결과입니다. */
public record AdminReportResponse(
        Long reportId,
        Long groupId,
        Long reporterUserId,
        Long reportedUserId,
        String category,
        String content,
        String status,
        Instant createdAt,
        Instant handledAt,
        String reportedUserNickname,
        String reporterNickname,
        Long handledByUserId,
        String resolution
) {
}
