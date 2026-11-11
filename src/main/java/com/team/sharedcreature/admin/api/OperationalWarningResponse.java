package com.team.sharedcreature.admin.api;

import java.time.Instant;

/** 현재 원본 상태에서 합성한 미해결 운영 경고입니다. */
public record OperationalWarningResponse(
        String warningKey,
        String type,
        String severity,
        String resourceType,
        Long resourceId,
        String message,
        Instant detectedAt
) {
}
