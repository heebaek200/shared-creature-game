package com.team.sharedcreature.admin.api;

import com.team.sharedcreature.group.domain.UserReportCategory;
import com.team.sharedcreature.group.domain.UserReportStatus;

import java.time.Instant;

/** 관리자 신고 목록에 노출하는 최소 필드입니다. */
public record AdminReportListItemResponse(
        Long reportId,
        Long groupId,
        Long reporterUserId,
        Long reportedUserId,
        UserReportCategory category,
        String content,
        UserReportStatus status,
        Instant createdAt,
        Instant handledAt
) {
}
