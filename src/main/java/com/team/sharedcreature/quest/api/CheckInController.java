package com.team.sharedcreature.quest.api;

import com.team.sharedcreature._core.api.ApiResponse;
import com.team.sharedcreature._core.security.LoginUser;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 서비스 체크인 API 요청을 처리합니다.
 */
@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
public class CheckInController {

    /** 체크인 저장과 기상 퀘스트 완료 처리를 담당합니다. */
    private final CheckInService checkInService;

    /**
     * 로그인한 사용자의 오늘 체크인을 처리합니다.
     *
     * @param loginUser 인증된 사용자 정보
     * @return 체크인 결과와 자동 완료된 퀘스트 ID 목록을 담은 공통 응답
     */
    @PostMapping("/check-ins")
    public ApiResponse<CheckInResponse> createCheckIn(
            @AuthenticationPrincipal LoginUser loginUser
    ) {
        CheckInResponse response = checkInService.createCheckIn(loginUser.id());
        return ApiResponse.success(response);
    }
}