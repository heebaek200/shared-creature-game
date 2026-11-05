package com.team.sharedcreature.group.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record RemoveGroupMemberRequest (
        @NotBlank @Size(max = 500) String reason
) {

    public RemoveGroupMemberRequest {
        if (reason != null) {
            // 요청의 강퇴 사유 양쪽 공백 제거
            reason = reason.strip();
        }
    }

}
