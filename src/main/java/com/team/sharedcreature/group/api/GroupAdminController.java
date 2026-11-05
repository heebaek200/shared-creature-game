package com.team.sharedcreature.group.api;

import com.team.sharedcreature._core.api.ApiResponse;
import com.team.sharedcreature._core.security.LoginUser;
import com.team.sharedcreature.group.application.GroupAdminService;
import com.team.sharedcreature.group.application.GroupInvitationManagementService;
import com.team.sharedcreature.group.application.GroupRemovalService;
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
    private final GroupAdminService groupAdminService;
    private final GroupRemovalService groupRemovalService;
    private final GroupInvitationManagementService invitationManagementService;

    public GroupAdminController(
            GroupAdminService groupAdminService,
            GroupRemovalService groupRemovalService,
            GroupInvitationManagementService invitationManagementService
    ) {
        this.groupAdminService = groupAdminService;
        this.groupRemovalService = groupRemovalService;
        this.invitationManagementService = invitationManagementService;
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

    /**
     * POST /api/v1/groups/me/members/{userId}/removals
     * 현재 그룹의 비활동 회원에 대한 강퇴 요청을 처리
     */
    @PostMapping("/members/{userId}/removals")
    public ApiResponse<LeaveMyGroupResponse> removeInactiveMember(
            @AuthenticationPrincipal LoginUser loginUser,
            @PathVariable @Positive Long userId,
            @Valid @RequestBody RemoveGroupMemberRequest request
    ) {
        // 세션에서 인증 사용자 ID, 요청에서 대상과 사유
        LeaveMyGroupResponse result = groupRemovalService.removeInactiveMember(
                loginUser.id(),
                userId,
                request.reason()
        );

        return ApiResponse.success(result);
    }

    /**
     * GET /api/v1/groups/me/invitation
     * 현재 활성 초대 링크의 관리 정보를 조회
     *  - 주 관리자(PRIMARY) 만 요청 가능
     *  - 이전 초대 원문 토큰이나 URL은 반환하지 않음
     *  - 발급 시각과 발급자 등 메타데이터만 제공
     */
    @GetMapping("/invitation")
    public ApiResponse<InvitationManagementResponse> getInvitationManagement(
            @AuthenticationPrincipal LoginUser loginUser
    ) {
        // 최신 멤버십 역할 검증은 Service에서 다시 수행
        return ApiResponse.success(
                invitationManagementService.getInvitationManagement(loginUser.id())
        );
    }

    /**
     * POST /api/v1/groups/me/invitation/rotate
     * 현재 그룹의 초대 링크를 새 토큰으로 재발급합니다.
     *
     * PRIMARY 관리자만 요청할 수 있습니다.
     * 이전 링크를 무효화하고 새 링크를 원자적으로 발급합니다.
     * 새 초대 URL은 이 응답에서만 반환합니다.
     */
    @PostMapping("/invitation/rotate")
    public ApiResponse<RotateInvitationResponse> rotateInvitation(
            @AuthenticationPrincipal LoginUser loginUser
    ) {
        // POST 요청에는 기존 Spring Security CSRF 검증이 적용됩니다.
        return ApiResponse.success(
                invitationManagementService.rotateInvitation(loginUser.id())
        );
    }
}
