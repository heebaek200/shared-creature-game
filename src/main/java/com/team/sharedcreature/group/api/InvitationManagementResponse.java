package com.team.sharedcreature.group.api;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Instant;

/**
 * 현재 활성 초대 링크의 관리 정보를 반환합니다.
 *
 * 이전 초대 토큰 원문이나 초대 URL은 포함하지 않습니다.
 * 사용 횟수는 무제한이며 만료 시각은 null입니다.
 * expiresAt은 API 계약상 필수 필드이므로 null도 출력합니다.
 */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record InvitationManagementResponse(
        Long groupId,
        Instant issuedAt,
        Long issuedByUserId,
        boolean unlimitedUses,
        Instant expiresAt
) {}
