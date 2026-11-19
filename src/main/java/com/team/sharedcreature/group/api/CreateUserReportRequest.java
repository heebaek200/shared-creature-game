package com.team.sharedcreature.group.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

/** 사용자 신고 접수 입력입니다. */
public record CreateUserReportRequest(
        @NotNull @Positive Long reportedUserId,
        @NotBlank @Pattern(regexp = "HARASSMENT|SPAM|INAPPROPRIATE_CONTENT|OTHER") String category,
        @NotBlank @Size(max = 1000) String content,
        @Pattern(regexp = "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[1-5][0-9a-fA-F]{3}-[89abAB][0-9a-fA-F]{3}-[0-9a-fA-F]{12}$")
        String chatMessageId
) {
    /** 기존 일반 신고 호출부와의 소스 호환성을 유지합니다. */
    public CreateUserReportRequest(Long reportedUserId, String category, String content) {
        this(reportedUserId, category, content, null);
    }
}
