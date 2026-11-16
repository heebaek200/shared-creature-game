package com.team.sharedcreature.admin.api;

import com.team.sharedcreature._core.api.ApiResponse;
import com.team.sharedcreature.admin.application.OperationalWarningService;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** 전체 관리자의 현재 운영 경고 REST 요청을 처리합니다. */
@Validated
@RestController
@RequestMapping("/api/v1/admin/operations")
public class AdminOperationsController {

    private final OperationalWarningService warningService;

    /** 현재 운영 경고 Service를 주입받습니다. */
    public AdminOperationsController(OperationalWarningService warningService) {
        this.warningService = warningService;
    }

    /** 미해결 원본 경고를 유형별로 페이지 조회합니다. */
    @GetMapping("/warnings")
    public ApiResponse<List<OperationalWarningResponse>> listWarnings(
            @RequestParam(required = false)
            @Pattern(regexp = "SCHEDULER_FAILED|SEASON_STATE_INVALID|TEMPLATE_SHORTAGE|QUEST_PERIOD_FAILED|MEMBER_COUNT_MISMATCH|PAYMENT_UNCERTAIN|REFUND_UNCERTAIN|PHOTO_DELETION_FAILED")
            String type,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(50) int size
    ) {
        return ApiResponse.page(warningService.list(type, page, size));
    }
}
