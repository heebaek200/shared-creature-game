package com.team.sharedcreature.ranking.api;

import com.team.sharedcreature._core.exception.BusinessException;
import com.team.sharedcreature._core.exception.ErrorCode;
import com.team.sharedcreature._core.security.LoginUser;
import com.team.sharedcreature.ranking.application.RankingService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 개인·그룹 시즌 랭킹 조회 요청을 처리합니다.
 * Query 값을 검증하고 랭킹 조회는 Service에 위임합니다.
 */
@RequiredArgsConstructor
@RestController
@RequestMapping("/api/v1/rankings")
public class RankingController {

    private final RankingService rankingService;

    /**
     * 현재 소속 그룹의 개인 시즌 랭킹을 조회합니다.
     *
     * @param loginUser 인증된 사용자
     * @param seasonId 시즌 ID, 생략하면 현재 시즌
     * @param page 페이지 번호, 생략하면 0
     * @param size 페이지 크기, 생략하면 20
     * @return 개인 랭킹과 현재 사용자의 순위
     */
    @GetMapping("/members")
    public RankingResponse.PageDto<RankingResponse.MemberDto> getMemberRanking(
            @AuthenticationPrincipal LoginUser loginUser, // 인증 사용자는 Query로 받지 않습니다.
            @RequestParam(name = "seasonId", required = false) String seasonId,
            @RequestParam(name = "page", required = false) String page,
            @RequestParam(name = "size", required = false) String size
    ) {
        Long requestedSeasonId = parseNumber(
                seasonId, null, 1, Long.MAX_VALUE, "seasonId"
        );
        int pageNumber = parseNumber(
                page, 0L, 0, Integer.MAX_VALUE, "page"
        ).intValue();
        int pageSize = parseNumber(
                size, 20L, 1, 50, "size"
        ).intValue();

        return rankingService.getMemberRanking(
                loginUser.id(),
                requestedSeasonId,
                pageNumber,
                pageSize
        );
    }

    /**
     * 전체 그룹의 시즌 랭킹을 조회합니다.
     * 그룹 미가입자도 인증되어 있으면 조회할 수 있습니다.
     *
     * @param loginUser 인증된 사용자
     * @param seasonId 시즌 ID, 생략하면 현재 시즌
     * @param page 페이지 번호, 생략하면 0
     * @param size 페이지 크기, 생략하면 20
     * @return 그룹 랭킹과 현재 소속 그룹의 순위
     */
    @GetMapping("/groups")
    public RankingResponse.PageDto<RankingResponse.GroupDto> getGroupRanking(
            @AuthenticationPrincipal LoginUser loginUser,
            @RequestParam(name = "seasonId", required = false) String seasonId,
            @RequestParam(name = "page", required = false) String page,
            @RequestParam(name = "size", required = false) String size
    ) {
        Long requestedSeasonId = parseNumber(
                seasonId, null, 1, Long.MAX_VALUE, "seasonId"
        );
        int pageNumber = parseNumber(
                page, 0L, 0, Integer.MAX_VALUE, "page"
        ).intValue();
        int pageSize = parseNumber(
                size, 20L, 1, 50, "size"
        ).intValue();

        return rankingService.getGroupRanking(
                loginUser.id(),
                requestedSeasonId,
                pageNumber,
                pageSize
        );
    }

    /**
     * 선택적인 정수 Query의 형식과 범위를 검사합니다.
     *
     * @param value 입력 문자열
     * @param defaultValue 생략 시 기본값
     * @param min 허용 최솟값
     * @param max 허용 최댓값
     * @param fieldName 오류 안내에 사용할 필드명
     * @return 검증된 정수 또는 생략 시 기본값
     */
    private Long parseNumber(
            String value,
            Long defaultValue,
            long min,
            long max,
            String fieldName
    ) {
        // 생략한 경우에만 기본값을 적용합니다.
        if (value == null) {
            return defaultValue;
        }

        long parsed;
        try {
            parsed = Long.parseLong(value);
        } catch (NumberFormatException exception) {
            throw new BusinessException(
                    ErrorCode.INVALID_FIELD_VALUE,
                    fieldName + "는 정수로 입력해 주세요."
            );
        }

        // 범위를 벗어난 값을 자동으로 보정하지 않습니다.
        if (parsed < min || parsed > max) {
            throw new BusinessException(
                    ErrorCode.INVALID_FIELD_VALUE,
                    fieldName + "의 허용 범위를 확인해 주세요."
            );
        }

        return parsed;
    }
}