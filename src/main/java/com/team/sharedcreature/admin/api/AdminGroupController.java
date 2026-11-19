
package com.team.sharedcreature.admin.api;

import com.team.sharedcreature._core.api.ApiResponse;
import com.team.sharedcreature._core.security.LoginUser;
import com.team.sharedcreature.admin.application.AdminGroupQueryService;
import com.team.sharedcreature.admin.application.AdminGroupRecoveryService;
import com.team.sharedcreature.group.api.GroupMemberResponse;
import com.team.sharedcreature.group.api.MyGroupResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 전체 관리자의 그룹 조회 및 운영 복구 REST 요청을 처리합니다.
 *
 * 그룹 검색과 활성 회원 조회는 읽기 전용 Service에 위임합니다.
 * 기존 주 관리자 재지정 명령은 RecoveryService를 그대로 사용합니다.
 */
@Validated
@RestController
@RequestMapping("/api/v1/admin/groups")
public class AdminGroupController {

    private final AdminGroupQueryService queryService;
    private final AdminGroupRecoveryService recoveryService;

    /**
     * 그룹 조회 및 복구 Service를 주입받습니다.
     *
     * 조회와 상태 변경 업무를 분리합니다.
     * 모든 경로는 기존 SecurityConfiguration의 전체 관리자 권한 정책을 따릅니다.
     *
     * @param queryService 관리자 그룹 조회 Service
     * @param recoveryService 주 관리자 복구 Service
     */
    public AdminGroupController(
            AdminGroupQueryService queryService,
            AdminGroupRecoveryService recoveryService
    ) {
        this.queryService = queryService;
        this.recoveryService = recoveryService;
    }

    /**
     * 그룹 이름으로 전체 활성 그룹을 페이지 검색합니다.
     *
     * 빈 검색어는 전체 활성 그룹 조회를 의미합니다.
     * 공개 여부와 무관하게 검색하되 SUPER_ADMIN 권한을 요구합니다.
     *
     * @param q 그룹 이름 검색어
     * @param page 페이지 번호
     * @param size 페이지 크기
     * @return 그룹 목록과 공통 페이지 메타데이터
     */
    @GetMapping
    public ApiResponse<List<AdminGroupSearchResponse>> searchGroups(
            @RequestParam(defaultValue = "")
            @Size(max = 30) String q,
            @RequestParam(defaultValue = "0")
            @Min(0) int page,
            @RequestParam(defaultValue = "20")
            @Min(1) @Max(50) int size
    ) {
        return ApiResponse.page(
                queryService.searchGroups(q, page, size)
        );
    }

    /**
     * 지정한 활성 그룹의 구성원을 페이지 조회합니다.
     *
     * 해당 그룹의 멤버십 상태가 ACTIVE인 구성원만 반환합니다.
     * 닉네임, 사용자 ID, 역할을 포함해 관리자 선택 UI에 제공합니다.
     *
     * @param groupId 그룹 PK
     * @param page 페이지 번호
     * @param size 페이지 크기
     * @return 활성 구성원 목록과 페이지 메타데이터
     */
    @GetMapping("/{groupId}/members")
    public ApiResponse<List<GroupMemberResponse>> listMembers(
            @PathVariable @Positive Long groupId,
            @RequestParam(defaultValue = "0")
            @Min(0) int page,
            @RequestParam(defaultValue = "20")
            @Min(1) @Max(50) int size
    ) {
        return ApiResponse.page(
                queryService.listMembers(groupId, page, size)
        );
    }

    /**
     * 활성 그룹의 주 관리자를 다른 활성 회원으로 재지정합니다.
     *
     * 기존 RecoveryService의 잠금·동시성 검증과 감사 처리를 유지합니다.
     * 새로운 조회 API는 이 명령의 권한 및 검증 규칙을 변경하지 않습니다.
     *
     * @param loginUser 현재 로그인한 전체 관리자
     * @param groupId 대상 그룹 PK
     * @param request 변경 대상 사용자와 사유
     * @return 변경된 그룹 정보
     */
    @PostMapping("/{groupId}/primary-transfer")
    public ApiResponse<MyGroupResponse> transferPrimary(
            @AuthenticationPrincipal LoginUser loginUser,
            @PathVariable @Positive Long groupId,
            @Valid @RequestBody SuperAdminPrimaryTransferRequest request
    ) {
        return ApiResponse.success(recoveryService.transfer(
                loginUser.id(),
                groupId,
                request.targetUserId(),
                request.reason()
        ));
    }
}
