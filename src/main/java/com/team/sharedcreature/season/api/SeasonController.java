package com.team.sharedcreature.season.api;

import com.team.sharedcreature._core.api.ApiResponse;
import com.team.sharedcreature.season.application.SeasonQueryService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 현재 시즌 REST 조회 요청을 처리합니다. */
@RestController
@RequestMapping("/api/v1/seasons")
public class SeasonController {

    private final SeasonQueryService seasonQueryService;

    /** 현재 시즌 조회 Service를 주입받습니다. */
    public SeasonController(SeasonQueryService seasonQueryService) {
        this.seasonQueryService = seasonQueryService;
    }

    /** 로그인 사용자가 현재 활성 시즌 시각과 상태를 조회합니다. */
    @GetMapping("/current")
    public ApiResponse<SeasonResponse> getCurrentSeason() {
        return ApiResponse.success(seasonQueryService.getCurrentSeason());
    }
}
