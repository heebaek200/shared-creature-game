package com.team.sharedcreature.admin.api;

import com.team.sharedcreature._core.api.ApiResponse;
import com.team.sharedcreature.admin.application.AdminDashboardService;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 전체 관리자 대시보드 집계 REST 요청을 처리합니다. */
@Validated
@RestController
@RequestMapping("/api/v1/admin/dashboard")
public class AdminDashboardController {

    private final AdminDashboardService dashboardService;

    /** 대시보드 집계 Service를 주입받습니다. */
    public AdminDashboardController(AdminDashboardService dashboardService) {
        this.dashboardService = dashboardService;
    }

    /** 기간과 그룹 필터에 맞는 서버 집계를 반환합니다. */
    @GetMapping
    public ApiResponse<AdminDashboardResponse> get(
            @RequestParam(defaultValue = "7_DAYS")
            @Pattern(regexp = "7_DAYS|30_DAYS|CURRENT_SEASON") String range,
            @RequestParam(required = false) @Positive Long groupId
    ) {
        return ApiResponse.success(dashboardService.get(range, groupId));
    }
}
