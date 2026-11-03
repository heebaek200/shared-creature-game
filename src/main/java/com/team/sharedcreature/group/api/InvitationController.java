package com.team.sharedcreature.group.api;

import com.team.sharedcreature._core.api.ApiResponse;
import com.team.sharedcreature.group.application.GroupInvitationService;
import jakarta.servlet.http.HttpSession;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 인증 전에도 사용할 수 있는 공개 초대 REST 흐름을 제공합니다. */
@RestController
@RequestMapping("/api/v1/invitations")
public class InvitationController {

    private final GroupInvitationService invitationService;

    /** 공개 초대 Service를 주입받습니다. */
    public InvitationController(GroupInvitationService invitationService) {
        this.invitationService = invitationService;
    }

    /** 초대 Token에 연결된 공개 그룹 상태를 반환합니다. */
    @GetMapping("/{token}")
    public ApiResponse<PublicInvitationResponse> getPublicInvitation(
            @PathVariable String token,
            HttpSession session
    ) {
        return ApiResponse.success(invitationService.getPublicInvitation(token, session));
    }

    /** 초대를 수락하고 인증 상태에 맞는 다음 경로를 반환합니다. */
    @PostMapping("/{token}/accept")
    public ApiResponse<NavigationResponse> acceptInvitation(@PathVariable String token, HttpSession session) {
        return ApiResponse.success(invitationService.acceptInvitation(token, session));
    }

    /** 초대를 거절하고 초대·가입 대기 문맥을 정리합니다. */
    @PostMapping("/{token}/reject")
    public ApiResponse<NavigationResponse> rejectInvitation(@PathVariable String token, HttpSession session) {
        return ApiResponse.success(invitationService.rejectInvitation(token, session));
    }
}
