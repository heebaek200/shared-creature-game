package com.team.sharedcreature.season.application;

import com.team.sharedcreature._core.exception.BusinessException;
import com.team.sharedcreature.season.api.SeasonResponse;
import com.team.sharedcreature.season.domain.Season;
import com.team.sharedcreature.season.infrastructure.SeasonRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 현재 활성 시즌의 공개 Snapshot 조회를 담당합니다. */
@Service
public class SeasonQueryService {

    private final SeasonRepository seasonRepository;

    /** 시즌 저장소를 주입받습니다. */
    public SeasonQueryService(SeasonRepository seasonRepository) {
        this.seasonRepository = seasonRepository;
    }

    /** 현재 활성 시즌을 반환하며 운영상 시즌이 없으면 일관된 업무 오류로 거절합니다. */
    @Transactional(readOnly = true)
    public SeasonResponse getCurrentSeason() {
        return SeasonResponse.from(findCurrentSeason());
    }

    /** 다른 시즌 업무 서비스가 같은 활성 시즌 판정을 재사용하도록 합니다. */
    @Transactional(readOnly = true)
    public Season findCurrentSeason() {
        return seasonRepository.findFirstByStatusOrderByStartAtDesc("ACTIVE")
                .orElseThrow(() -> new BusinessException(
                        HttpStatus.CONFLICT,
                        "CURRENT_SEASON_UNAVAILABLE",
                        "현재 진행 중인 시즌이 없습니다."
                ));
    }
}
