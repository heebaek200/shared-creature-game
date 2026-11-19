package com.team.sharedcreature.admin.api;

import java.time.Instant;

/** 관리자 신고 상세에만 노출하는 서버 검증 채팅 증거입니다. */
public record AdminReportChatEvidenceResponse(
        String messageId,
        Long senderUserId,
        String senderNickname,
        String content,
        Instant sentAt
) {
}
