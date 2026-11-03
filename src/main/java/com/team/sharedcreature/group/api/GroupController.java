package com.team.sharedcreature.group.api;

import com.team.sharedcreature._core.api.ApiResponse;
import com.team.sharedcreature._core.security.LoginUser;
import com.team.sharedcreature._core.security.SessionAuthenticationFilter;
import com.team.sharedcreature.group.application.GroupMembershipService;
import jakarta.servlet.http.HttpSession;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

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
}
