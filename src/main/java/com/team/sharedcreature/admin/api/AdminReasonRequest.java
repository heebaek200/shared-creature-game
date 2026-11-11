package com.team.sharedcreature.admin.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** 신고 기각·강퇴의 관리자 사유 입력입니다. */
public record AdminReasonRequest(@NotBlank @Size(max = 1000) String reason) {
}
