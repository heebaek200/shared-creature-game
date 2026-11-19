package com.team.sharedcreature.group.api;

import jakarta.validation.constraints.NotNull;

/** 그룹 채팅 입력입니다. 코드 포인트 검증은 Service가 수행합니다. */
public record CreateGroupChatMessageRequest(@NotNull String content) {
}
