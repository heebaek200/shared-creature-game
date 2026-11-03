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
        Long inviteLinkId,
        Instant expiresAt
) implements Serializable {
    /** 공급자 검증 정보는 유지하면서 마지막으로 수락한 초대로 교체합니다. */
    public PendingSignup withInviteLinkId(Long latestInviteLinkId) {
        return new PendingSignup(
                provider,
                providerUserId,
                email,
                emailVerified,
                suggestedNickname,
                latestInviteLinkId,
                expiresAt
        );
    }
}
