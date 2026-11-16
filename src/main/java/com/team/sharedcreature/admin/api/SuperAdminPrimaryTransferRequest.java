package com.team.sharedcreature.admin.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

/** 전체 관리자의 주 관리자 복구 입력입니다. */
public record SuperAdminPrimaryTransferRequest(
        @NotNull @Positive Long targetUserId,
        @NotBlank @Size(max = 500) String reason
) {
}
