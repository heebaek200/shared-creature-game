package com.team.sharedcreature.quest.api;

import com.team.sharedcreature._core.api.ApiResponse;
import com.team.sharedcreature._core.exception.BusinessException;
import com.team.sharedcreature._core.security.LoginUser;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.http.HttpStatus;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * 퀘스트 조회 요청을 처리합니다.
 */
@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
public class QuestController {

    /** 퀘스트 조회와 완료 업무를 처리합니다. */
    private final QuestCompletionService questCompletionService;

    /**
     * 현재 사용자의 일일·주간 퀘스트와 완료 상태를 조회합니다.
     *
     * @param loginUser 인증된 사용자 정보
     * @param request 조회 기간 유형을 담은 Query Parameter DTO
     * @return 퀘스트 목록을 담은 공통 성공 응답
     */
    @GetMapping("/quests/current")
    public ApiResponse<List<CurrentQuestResponse>> listCurrentQuests(
            /**AuthenticationPrincipal : 인증된 사용자 정보 주입 어노테이션*/
            @AuthenticationPrincipal LoginUser loginUser,
            @RequestParam(name = "periodType", required = false) String periodType
    ) {
        List<CurrentQuestResponse> quests = questCompletionService.listCurrentQuests(
                loginUser.id(),
                periodType
        );

        return ApiResponse.success(quests);
    }

    /**
     * SELF_CHECK 퀘스트 완료를 요청하고 보상 결과를 반환합니다.
     *
     * @param loginUser 인증된 사용자 정보
     * @param assignmentId 완료할 퀘스트 배정 ID
     * @return 완료와 보상 반영 결과
     */
    @PostMapping("/quest-assignments/{assignmentId}/completions")
    public ApiResponse<QuestCompletionResponse> completeQuestAssignment(
            @AuthenticationPrincipal LoginUser loginUser,
            @PathVariable Long assignmentId
    ) {
        QuestCompletionResponse result =
                questCompletionService.completeQuestAssignment(loginUser.id(), assignmentId);

        return ApiResponse.success(result);
    }
}
