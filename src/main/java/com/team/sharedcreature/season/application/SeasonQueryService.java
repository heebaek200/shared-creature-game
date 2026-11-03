package com.team.sharedcreature.season.application;

import com.team.sharedcreature._core.exception.BusinessException;
import com.team.sharedcreature.season.api.SeasonResponse;
import com.team.sharedcreature.season.domain.Season;
import com.team.sharedcreature.season.infrastructure.SeasonRepository;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

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
                        HttpStatus.NOT_FOUND,
                        "RESOURCE_NOT_FOUND",
                        "현재 시즌을 찾을 수 없습니다."
                ));
    }

    /** 시즌 종료와 경합하는 명령이 시즌 행 공유 잠금을 가장 먼저 획득하도록 합니다. */
    @Transactional
    public Season findCurrentSeasonForShare() {
        return findCurrentSeasonForShareIfPresent()
                .orElseThrow(() -> new BusinessException(
                        HttpStatus.NOT_FOUND,
                        "RESOURCE_NOT_FOUND",
                        "현재 시즌을 찾을 수 없습니다."
                ));
    }

    /** Scheduler가 시즌 종료 선점 뒤에는 오류 없이 작업을 건너뛸 수 있도록 합니다. */
    @Transactional
    public Optional<Season> findCurrentSeasonForShareIfPresent() {
        return seasonRepository.findForShareByStatus("ACTIVE", PageRequest.of(0, 1)).stream().findFirst();
    }
}
