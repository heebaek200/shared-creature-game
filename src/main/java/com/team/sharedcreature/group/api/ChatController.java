package com.team.sharedcreature.group.api;

import com.team.sharedcreature._core.api.ApiResponse;
import com.team.sharedcreature._core.security.LoginUser;
import com.team.sharedcreature.group.application.GroupChatService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 현재 그룹원의 말풍선 채팅 REST 요청을 처리합니다. */
@RestController
@RequestMapping("/api/v1/groups/me/chat")
public class ChatController {

    private final GroupChatService chatService;

    /** 채팅 명령 Service를 주입받습니다. */
    public ChatController(GroupChatService chatService) {
        this.chatService = chatService;
    }

    /** 메시지를 저장한 뒤 서버 확정 DTO를 반환합니다. */
    @PostMapping
    public ResponseEntity<ApiResponse<GroupChatMessageResponse>> create(
            @AuthenticationPrincipal LoginUser loginUser,
            @Valid @RequestBody CreateGroupChatMessageRequest request
    ) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.success(chatService.create(loginUser.id(), request.content())));
    }
}
