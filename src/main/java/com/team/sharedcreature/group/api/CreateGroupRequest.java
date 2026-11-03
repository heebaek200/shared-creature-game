package com.team.sharedcreature.group.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 새 공개 그룹 생성 입력입니다.
 *
 * @param name 그룹 표시 이름
 * @param description 선택적인 그룹 소개
 */
public record CreateGroupRequest(
        @NotBlank @Size(min = 2, max = 30) String name,
        @Size(max = 500) String description
) {
}
