package com.team.sharedcreature.season.application;

import com.team.sharedcreature._core.exception.BusinessException;
import com.team.sharedcreature._core.exception.ErrorCode;
import com.team.sharedcreature.season.api.SeasonResponse;
import com.team.sharedcreature.season.domain.Season;
import com.team.sharedcreature.season.infrastructure.SeasonRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * 현재 활성 시즌의 공개 Snapshot 조회를 담당합니다.
 */
@Slf4j
@Service
public class SeasonQueryService {

    private final SeasonRepository seasonRepository;
    private final Clock clock;

    public SeasonQueryService(SeasonRepository seasonRepository, Clock clock) {
        this.seasonRepository = seasonRepository;
        this.clock = clock;
    }

    /** 현재 활성 시즌을 반환하며 운영상 시즌이 없으면 일관된 업무 오류로 거절합니다. */
    @Transactional(readOnly = true)
    public SeasonResponse getCurrentSeason() {
        return SeasonResponse.from(findCurrentSeason());
    }

    /**
     * 랭킹에서 조회할 수 있는 현재·종료 시즌을 최신 순으로 반환합니다.
     * Scheduler 상태 전환이 지연된 만료 ACTIVE 시즌은 선택 목록에서 제외합니다.
     *
     * @return 현재 시각에 유효한 ACTIVE 시즌과 종료된 시즌 목록
     */
    @Transactional(readOnly = true)
    public List<SeasonResponse> listRankableSeasons() {
        Instant now = Instant.now(clock);

        return seasonRepository
                .findAllRankableOrderBySeasonNumberDesc()
                .stream()
                .filter(season -> "ENDED".equals(season.getStatus()) || season.isActiveAt(now))
                .map(SeasonResponse::from)
                .toList();
    }

    /**
     * 현재 유효한 ACTIVE 시즌을 조회하는 기능
     */
    @Transactional(readOnly = true)
    public Season findCurrentSeason() {
        Season season = seasonRepository
                .findFirstByStatusOrderByStartAtDesc("ACTIVE")
                .orElseThrow(this::seasonNotReady);

        // 후보를 조회하고 실제 시각으로 재판정.
        Instant now = Instant.now(clock);

        if (!season.isActiveAt(now)) {
            throw seasonNotReady();
        }

        return season;
    }

    /** 시즌 종료와 경합하는 명령이 시즌 행 공유 잠금을 가장 먼저 획득하도록 합니다. */
    @Transactional
    public Season findCurrentSeasonForShare() {
        return findCurrentSeasonForShareIfPresent()
                .orElseThrow(this::seasonNotReady);
    }

    /**
     * Scheduler가 시즌 종료 선점 뒤에는 오류 없이 작업을 건너뛸 수 있도록 합니다.
     * 시즌이 없거나 시간 경계를 벗어나면 빈 결과를 반환함.
     */
    @Transactional
    public Optional<Season> findCurrentSeasonForShareIfPresent() {
        Optional<Season> candidate = seasonRepository
                .findForShareByStatus("ACTIVE", PageRequest.of(0, 1))
                .stream().findFirst();

        // DB 공유 잠금 획득 후의 시각 사용
        Instant now = Instant.now(clock);

        return candidate.filter(season -> season.isActiveAt(now));
    }

    /**
     * 현재 시즌이 업무를 처리할 준비가 되지 않았음을 나타냄
     */
    private BusinessException seasonNotReady() {
        return new BusinessException(ErrorCode.CONCURRENT_MODIFICATION, "현재 시즌이 준비되지 않았습니다.");
    }

}
