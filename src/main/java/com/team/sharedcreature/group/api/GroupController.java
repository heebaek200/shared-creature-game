package com.team.sharedcreature.group.api;

import com.team.sharedcreature._core.api.ApiResponse;
import com.team.sharedcreature._core.security.LoginUser;
import com.team.sharedcreature.group.application.GroupMembershipService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 공개 그룹 생성과 멤버십 REST 요청을 처리합니다.
 */
@RestController
@RequestMapping("/api/v1/groups")
public class GroupController {

    private final GroupMembershipService groupMembershipService;

    /** 그룹 업무 Service를 주입받습니다. */
    public GroupController(GroupMembershipService groupMembershipService) {
        this.groupMembershipService = groupMembershipService;
    }

    /**
     * 새 공개 그룹을 만들고 요청자를 주 관리자로 가입시킵니다.
     *
     * @param loginUser 인증 사용자
     * @param request 그룹 생성 입력
     * @return 생성된 그룹과 최초 초대 URL
     */
    @PostMapping
    public ResponseEntity<ApiResponse<CreateGroupResponse>> createGroup(
            @AuthenticationPrincipal LoginUser loginUser,
            @Valid @RequestBody CreateGroupRequest request
    ) {
        CreateGroupResponse result = groupMembershipService.createGroup(loginUser, request);
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.success(result));
    }
}
