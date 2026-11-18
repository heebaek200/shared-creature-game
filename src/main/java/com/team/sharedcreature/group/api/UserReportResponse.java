package com.team.sharedcreature.group.api;

import com.team.sharedcreature.group.domain.UserReportCategory;
import com.team.sharedcreature.group.domain.UserReportStatus;

import java.time.Instant;

/** 신고 접수 및 목록의 공통 공개 필드입니다. */
public record UserReportResponse(
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
