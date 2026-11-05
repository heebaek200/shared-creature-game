package com.team.sharedcreature.group.api;

import com.team.sharedcreature._core.api.ApiResponse;
import com.team.sharedcreature._core.security.LoginUser;
import com.team.sharedcreature._core.security.SessionAuthenticationFilter;
import com.team.sharedcreature.group.application.GroupMembershipService;
import com.team.sharedcreature.group.application.GroupQueryService;
import jakarta.servlet.http.HttpSession;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;


import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 공개 그룹 생성과 멤버십 REST 요청을 처리합니다.
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/groups")
public class GroupController {

    private final GroupMembershipService groupMembershipService;
    private final GroupQueryService groupQueryService;

    /** 그룹 업무 Service를 주입받습니다. */
    public GroupController(GroupMembershipService groupMembershipService, GroupQueryService groupQueryService) {
        this.groupMembershipService = groupMembershipService;
        this.groupQueryService = groupQueryService;
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
            @Valid @RequestBody CreateGroupRequest request,
            HttpSession session
    ) {
        CreateGroupResponse result = groupMembershipService.createGroup(loginUser, request);
        updateSessionRole(loginUser, result.group().myMembership().role(), session);
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.success(result));
    }

    /** 공개 활성 그룹을 검색어와 고정 정렬로 페이지 조회합니다. */
    @GetMapping
    public ApiResponse<List<GroupSummaryResponse>> listPublicGroups(
            @AuthenticationPrincipal LoginUser loginUser,
            @RequestParam(defaultValue = "") @jakarta.validation.constraints.Size(max = 30) String q,
            @RequestParam(defaultValue = "0") @jakarta.validation.constraints.Min(0) int page,
            @RequestParam(defaultValue = "20")
            @jakarta.validation.constraints.Min(1) @jakarta.validation.constraints.Max(50) int size
    ) {
        return ApiResponse.page(groupMembershipService.listPublicGroups(loginUser, q, page, size));
    }

    /** 인증 사용자를 공개 그룹의 일반 구성원으로 가입시킵니다. */
    @PostMapping("/{groupId}/memberships")
    public ResponseEntity<ApiResponse<MembershipResponse>> joinGroup(
            @AuthenticationPrincipal LoginUser loginUser,
            @PathVariable Long groupId,
            HttpSession session
    ) {
        MembershipResponse result = groupMembershipService.joinGroup(loginUser, groupId);
        updateSessionRole(loginUser, result.role(), session);
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.success(result));
    }

    private void updateSessionRole(LoginUser loginUser, String role, HttpSession session) {
        var roles = new HashSet<>(loginUser.roles());
        roles.add(role);
        session.setAttribute(
                SessionAuthenticationFilter.LOGIN_USER,
                new LoginUser(loginUser.id(), loginUser.nickname(), Set.copyOf(roles))
        );
    }

    /**
     * 현재 인증 사용자가 소속된 그룹의 상세 정보를 조회합니다.
     *
     * 사용자 ID로 활성 멤버십을 다시 확인합니다.
     * 그룹 정보와 주 관리자 및 자신의 멤버십을 공통 응답 형식으로 반환합니다.
     *
     * @param loginUser 인증 사용자
     * @return 현재 그룹 상세
     */
    @GetMapping("/me")
    public ApiResponse<MyGroupResponse> getMyGroup(
            @AuthenticationPrincipal LoginUser loginUser
    ) {
        // 현재 DB의 그룹 상태를 기준으로 조회합니다.
        return ApiResponse.success(
                groupQueryService.getMyGroup(loginUser.id())
        );
    }

    /**
     * 현재 그룹의 활성 구성원을 페이지 단위로 조회합니다.
     *
     * 페이지는 0부터 시작하고 기본 크기는 20명입니다.
     * 한 번에 최대 50명까지 조회할 수 있으며 정렬은 서버에서 고정합니다.
     *
     * @param loginUser 인증 사용자
     * @param page 페이지 번호
     * @param size 페이지 크기
     * @return 구성원 목록과 페이지 메타데이터
     */
    @GetMapping("/me/members")
    public ApiResponse<List<GroupMemberResponse>> listMyGroupMembers(
            @AuthenticationPrincipal LoginUser loginUser,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20")
            @Min(1) @Max(50) int size
    ) {
        // 기존 공통 페이지 응답 생성기를 재사용합니다.
        return ApiResponse.page(
                groupQueryService.listMyGroupMembers(
                        loginUser.id(),
                        page,
                        size
                )
        );
    }

    /**
     * DELETE /api/v1/groups/me/membership
     * 현재 인증 사용자의 그룹 자진 탈퇴 처리
     *  - 탈퇴 성공 후 세션에서 그룹 역할만 제거
     *  - 소셜 로그인 세션 자체는 유지하여 그룹 선택 화면으로 이동
     */
    @DeleteMapping("/me/membership")
    public ApiResponse<LeaveMyGroupResponse> leaveMyGroup(
            @AuthenticationPrincipal LoginUser loginUser,
            HttpSession session
    ) {
        LeaveMyGroupResponse result = groupMembershipService.leaveMyGroup(loginUser.id());

        // Service 트랜잭션이 정상 commit 된 후 세션에서 역할을 정리함.
        // 주 관리자, 부 관리자, 회원 역할은 삭제. 전체 관리자는 제외.
        Set roles = new HashSet<>(loginUser.roles());
        roles.removeAll(Set.of("PRIMARY", "DEPUTY", "MEMBER"));

        session.setAttribute(
                SessionAuthenticationFilter.LOGIN_USER,
                new LoginUser(
                        loginUser.id(),
                        loginUser.nickname(),
                        Set.copyOf(roles)
                )
        );

        return ApiResponse.success(result);
    }
}
