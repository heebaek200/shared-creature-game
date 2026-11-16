package com.team.sharedcreature.admin.api;

import com.team.sharedcreature._core.api.ApiResponse;
import com.team.sharedcreature._core.security.LoginUser;
import com.team.sharedcreature.admin.application.AdminGroupRecoveryService;
import com.team.sharedcreature.group.api.MyGroupResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 전체 관리자의 그룹 운영 복구 REST 요청을 처리합니다. */
@Validated
@RestController
@RequestMapping("/api/v1/admin/groups")
public class AdminGroupController {

    private final AdminGroupRecoveryService recoveryService;

    /** 주 관리자 복구 Service를 주입받습니다. */
    public AdminGroupController(AdminGroupRecoveryService recoveryService) {
        this.recoveryService = recoveryService;
    }

    /** 활성 그룹의 주 관리자를 다른 활성 회원으로 재지정합니다. */
    @PostMapping("/{groupId}/primary-transfer")
    public ApiResponse<MyGroupResponse> transferPrimary(
            @AuthenticationPrincipal LoginUser loginUser,
            @PathVariable @Positive Long groupId,
            @Valid @RequestBody SuperAdminPrimaryTransferRequest request
    ) {
        return ApiResponse.success(recoveryService.transfer(
                loginUser.id(), groupId, request.targetUserId(), request.reason()
        ));
    }
}
