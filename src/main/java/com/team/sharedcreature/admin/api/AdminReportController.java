package com.team.sharedcreature.admin.api;

import com.team.sharedcreature._core.api.ApiResponse;
import com.team.sharedcreature._core.security.LoginUser;
import com.team.sharedcreature.admin.application.AdminModerationService;
import com.team.sharedcreature.admin.application.AdminReportQueryService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
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

/** 전체 관리자의 신고 조회·기각·강퇴 REST 요청을 처리합니다. */
@Validated
@RestController
@RequestMapping("/api/v1/admin/reports")
public class AdminReportController {

    private final AdminReportQueryService queryService;
    private final AdminModerationService moderationService;

    /** 신고 조회와 처리 Service를 주입받습니다. */
    public AdminReportController(
            AdminReportQueryService queryService,
            AdminModerationService moderationService
    ) {
        this.queryService = queryService;
        this.moderationService = moderationService;
    }

    /** 상태 필터로 신고 목록을 조회합니다. */
    @GetMapping
    public ApiResponse<List<AdminReportResponse>> list(
            @RequestParam(required = false)
            @Pattern(regexp = "RECEIVED|RESOLVED|REJECTED") String status,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(50) int size
    ) {
        return ApiResponse.page(queryService.list(status, page, size));
    }

    /** 신고 상세와 처리 결과를 조회합니다. */
    @GetMapping("/{reportId}")
    public ApiResponse<AdminReportResponse> get(@PathVariable @Positive Long reportId) {
        return ApiResponse.success(queryService.get(reportId));
    }

    /** 접수 중인 신고를 기각합니다. */
    @PostMapping("/{reportId}/dismissals")
    public ApiResponse<AdminReportResponse> dismiss(
            @AuthenticationPrincipal LoginUser loginUser,
            @PathVariable @Positive Long reportId,
            @Valid @RequestBody AdminReasonRequest request
    ) {
        return ApiResponse.success(moderationService.dismiss(loginUser.id(), reportId, request.reason()));
    }

    /** 신고를 근거로 현재 활성 회원을 강퇴합니다. */
    @PostMapping("/{reportId}/removals")
    public ApiResponse<AdminReportResponse> remove(
            @AuthenticationPrincipal LoginUser loginUser,
            @PathVariable @Positive Long reportId,
            @Valid @RequestBody AdminReasonRequest request
    ) {
        return ApiResponse.success(moderationService.remove(loginUser.id(), reportId, request.reason()));
    }
}
