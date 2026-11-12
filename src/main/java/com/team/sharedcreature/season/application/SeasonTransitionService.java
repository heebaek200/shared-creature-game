package com.team.sharedcreature.season.application;

import com.team.sharedcreature.season.domain.Season;
import com.team.sharedcreature.season.infrastructure.SeasonRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.Objects;

/**
 * Scheduler 에서 사용하는 시즌 상태 전환을 담당하는 서비스.
 * 시즌 행의 배타 잠금으로 동시 상태 변경을 직렬화함.
 * 종료 준비 상태는 독립 트랜잭션에서 Commit한다.
 */
@Slf4j
@Service
public class SeasonTransitionService {

    private final SeasonRepository seasonRepository;
    private final Clock clock;

    public SeasonTransitionService(SeasonRepository seasonRepository, Clock clock) {
        this.seasonRepository = seasonRepository;
        this.clock = clock;
    }

    /**
     * 만료된 시즌을 CLOSING 상태로 전환한다.
     * 배타 잠금 획득 이후의 실제 시각으로 만료 여부를 판정한다.
     * ACTIVE 또는 SCHEDULED에서만 새로운 종료 처리를 시작한다.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean beginClosingIfExpired(Long seasonId) {
        Objects.requireNonNull(seasonId, "seasonId");

        // 같은 시즌의 상태 변경을 직렬화한다.
        Season season = seasonRepository.findForUpdateById(seasonId)
                .orElseThrow(() -> new IllegalStateException("종료 대상 시즌을 찾을 수 없습니다. seasonId=%d".formatted(seasonId)));

        // 잠금 대기 도중 종료 경계에 도달했는지 혹시 몰라 체크
        Instant now = Instant.now(clock);

        // 엔티티의 상태 전이 규칙 적용, Dirty Checking으로 반영
        return season.beginClosingIfExpired(now);
    }

}
