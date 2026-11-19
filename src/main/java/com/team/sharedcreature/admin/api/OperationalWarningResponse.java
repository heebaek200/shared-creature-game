package com.team.sharedcreature.admin.api;

import com.team.sharedcreature.admin.domain.OperationalWarningSeverity;
import com.team.sharedcreature.admin.domain.OperationalWarningType;

import java.time.Instant;

/** 현재 원본 상태에서 합성한 미해결 운영 경고입니다. */
public record OperationalWarningResponse(
        String warningKey,
        OperationalWarningType type,
        OperationalWarningSeverity severity,
        String resourceType,
        Long resourceId,
        String message,
        Instant detectedAt
) {
}
