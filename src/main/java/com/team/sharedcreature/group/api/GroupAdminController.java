package com.team.sharedcreature.group.api;

import com.team.sharedcreature._core.api.ApiResponse;
import com.team.sharedcreature._core.security.LoginUser;
import com.team.sharedcreature.group.application.GroupAdminService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@Slf4j
@RestController
@RequestMapping("/api/v1/groups/me")
public class GroupAdminController {
    private GroupAdminService groupAdminService;

    public GroupAdminController(GroupAdminService groupAdminService) {
        this.groupAdminService = groupAdminService;
    }

    /**
     * 구성원의 부 관리자 권한을 부여하거나 회수 요청 제한
     */
    public record ChangeMemberRoleRequest(
            @NotNull @Pattern(regexp = "MEMBER|DEPUTY") String role
    ) {}

    /**
     * 새 주 관리자로 지정할 사용자 ID 전달
     */
    public record TransferPrimaryRequest(
            @NotNull @Positive Long targetUserId
    ) {}

    /**
     * 구성원의 부 관리자 권한을 부여하거나 회수함
     * PATCH /api/v1/groups/me/members/{userId}/role
     */
    @PatchMapping("/members/{userId}/role")
    public ApiResponse<GroupMemberResponse> changeMemberRole(
            @AuthenticationPrincipal LoginUser loginUser,
            @PathVariable @Positive Long userId,
            @Valid @RequestBody ChangeMemberRoleRequest request
    ) {
        return ApiResponse.success(groupAdminService.changeMemberRole(
                loginUser.id(),
                userId,
                request.role()
        ));
    }

    /**
     * 현재 주 관리자 권한을 다른 활성 회원에게 이전
     *  - 이전한 전 주 관리자는 일반 회원(MEMBER)가 됨.
     *  - 변경한 그룹 상세를 반환함.
     * POST /api/v1/groups/me/primary-transfer
     */
    @PostMapping("/primary-transfer")
    public ApiResponse<MyGroupResponse> transferPrimaryAdmin(
            @AuthenticationPrincipal LoginUser loginUser,
            @Valid @RequestBody TransferPrimaryRequest request
    ) {
        return ApiResponse.success(groupAdminService.transferPrimaryAdmin(
                loginUser.id(),
                request.targetUserId()
        ));
    }
}
