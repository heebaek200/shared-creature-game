package com.team.sharedcreature.auth.application;

import java.io.Serializable;
import java.time.Instant;

/**
 * OAuth Callback과 닉네임 확정 사이의 검증된 가입 대기 정보입니다.
 */
public record PendingSignup(
        String provider,
        String providerUserId,
        String email,
        boolean emailVerified,
        String suggestedNickname,
        Instant expiresAt
) implements Serializable {
}
