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

            @ModelAttribute CurrentQuestsRequest request,
            @RequestParam MultiValueMap<String, String> queryParameters
    ) {
        validateCurrentQuestQuery(queryParameters);

        List<CurrentQuestResponse> quests = questCompletionService.listCurrentQuests(
                loginUser.id(),
                request.getPeriodType()
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

    /** 미등록 Query 키와 중복 Query 키를 잘못된 요청으로 거부합니다. */
    private void validateCurrentQuestQuery(MultiValueMap<String, String> queryParameters) {
        for (Map.Entry<String, List<String>> parameter : queryParameters.entrySet()) {
            if (!"periodType".equals(parameter.getKey())
                    || parameter.getValue().size() != 1) {
                throw new BusinessException(
                        HttpStatus.BAD_REQUEST,
                        "INVALID_REQUEST",
                        "Query Parameter를 확인해 주세요."
                );
            }

            String value = parameter.getValue().get(0);
            if (!"DAILY".equals(value) && !"WEEKLY".equals(value)) {
                throw new BusinessException(
                        HttpStatus.BAD_REQUEST,
                        "INVALID_FIELD_VALUE",
                        "periodType은 DAILY 또는 WEEKLY여야 합니다."
                );
            }
        }
    }
}
