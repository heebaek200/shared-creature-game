package com.team.sharedcreature.group.api;

import java.time.Instant;

/** 신고 접수 및 목록의 공통 공개 필드입니다. */
public record UserReportResponse(
        Long reportId,
        Long groupId,
        Long reporterUserId,
        Long reportedUserId,
        String category,
        String content,
        String status,
        Instant createdAt,
        Instant handledAt
) {
}
