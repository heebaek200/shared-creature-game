package com.team.sharedcreature.group.api;

import com.team.sharedcreature._core.api.ApiResponse;
import com.team.sharedcreature._core.security.LoginUser;
import com.team.sharedcreature.group.application.UserReportService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 그룹 회원의 사용자 신고 REST 요청을 처리합니다. */
@RestController
@RequestMapping("/api/v1/reports")
public class ReportController {

    private final UserReportService reportService;

    /** 신고 접수 Service를 주입받습니다. */
    public ReportController(UserReportService reportService) {
        this.reportService = reportService;
    }

    /** 같은 그룹의 활성 회원을 신고합니다. */
    @PostMapping
    public ResponseEntity<ApiResponse<UserReportResponse>> create(
            @AuthenticationPrincipal LoginUser loginUser,
            @Valid @RequestBody CreateUserReportRequest request
    ) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.success(reportService.create(loginUser.id(), request)));
    }
}
