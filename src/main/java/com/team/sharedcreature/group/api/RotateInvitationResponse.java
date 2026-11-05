package com.team.sharedcreature.group.api;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Instant;

/**
 * 초대 링크 재발급 결과를 반환합니다.
 *
 * 새 초대 URL은 재발급 성공 응답에서만 노출됩니다.
 * 데이터베이스에는 URL이나 토큰 원문을 저장하지 않습니다.
 * 클라이언트는 응답을 다시 조회할 수 없으므로 즉시 표시해야 합니다.
 */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record RotateInvitationResponse(
        Long groupId,
        String invitationUrl,
        Instant issuedAt,
        Long issuedByUserId,
        boolean unlimitedUses,
        Instant expiresAt
) {}
