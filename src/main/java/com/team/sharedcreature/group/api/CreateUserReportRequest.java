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
        @NotBlank @Size(max = 1000) String content
) {
}
