package com.team.sharedcreature.admin.api;

import com.team.sharedcreature.group.domain.UserReportCategory;
import com.team.sharedcreature.group.domain.UserReportStatus;

import java.time.Instant;

/** 전체 관리자가 조회하는 신고 상세와 처리 결과입니다. */
public record AdminReportResponse(
        Long reportId,
        Long groupId,
        Long reporterUserId,
        Long reportedUserId,
        UserReportCategory category,
        String content,
        UserReportStatus status,
        Instant createdAt,
        Instant handledAt,
        String reportedUserNickname,
        String reporterNickname,
        Long handledByUserId,
        String resolution
) {
}
