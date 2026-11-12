package com.team.sharedcreature.season.application;

import com.team.sharedcreature.season.domain.Season;
import com.team.sharedcreature.season.infrastructure.SeasonActivationInspectionRepository;
import com.team.sharedcreature.season.infrastructure.SeasonRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.Objects;

/**
 * SCHEDULED 시즌의 전체 그룹 초기화 완료를 확인한 뒤 ACTIVE로 전환합니다.
 * 시즌 행 배타 잠금을 유지한 채 DB 검증과 상태 변경을 하나의 트랜잭션으로 수행합니다.
 * 배치 초기화는 여기에서 호출하지 않으며 Commit된 초기화 결과만 검사합니다.
 */
@Service
public class SeasonActivationService {

    private final SeasonRepository seasonRepository;
    private final SeasonActivationInspectionRepository inspectionRepository;
    private final Clock clock;

    /**
     * 시즌 잠금, 전체 초기화 검사, 공통 Clock을 주입받습니다.
     * 검사 전과 후에 실제 시각을 확인해 시즌 시작·종료 경계의 오판을 방지합니다.
     * 그룹 초기화 Service는 별도 트랜잭션에서 선행 실행되어야 합니다.
     */
    public SeasonActivationService(
            SeasonRepository seasonRepository,
            SeasonActivationInspectionRepository inspectionRepository,
            Clock clock
    ) {
        this.seasonRepository = seasonRepository;
        this.inspectionRepository = inspectionRepository;
        this.clock = clock;
    }

    /**
     * 초기화가 완료된 현재 SCHEDULED 시즌을 ACTIVE로 확정합니다.
     * 누락·비정상 그룹이 있으면 SCHEDULED를 유지하고 검사 결과를 돌려줍니다.
     * 이미 ACTIVE거나 시작 전·만료 상태이면 중복 변경하지 않습니다.
     *
     * @param seasonId 활성화할 SCHEDULED 시즌 ID
     * @return 상태 변경 여부와 누락·비정상 초기화 검사 결과
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public ActivationResult activateIfInitializationComplete(Long seasonId) {
        Objects.requireNonNull(seasonId, "seasonId");

        // 모든 그룹 초기화 배치와 같은 순서로 시즌 행을 가장 먼저 배타 잠금합니다.
        Season season = seasonRepository.findForUpdateById(seasonId)
                .orElseThrow(() -> new IllegalStateException("시즌이 없습니다. seasonId=" + seasonId));
        if ("ACTIVE".equals(season.getStatus())) {
            return new ActivationResult(Outcome.ALREADY_ACTIVE, null);
        }
        if (!"SCHEDULED".equals(season.getStatus())) {
            return new ActivationResult(Outcome.NOT_SCHEDULED, null);
        }

        Instant beforeInspection = Instant.now(clock);
        if (beforeInspection.isBefore(season.getStartAt())) {
            return new ActivationResult(Outcome.NOT_STARTED, null);
        }
        if (!beforeInspection.isBefore(season.getEndAt())) {
            return new ActivationResult(Outcome.EXPIRED, null);
        }

        // 전체 활성 그룹의 참여·알·0점 집계가 완료됐는지 확인합니다.
        SeasonActivationInspectionRepository.Inspection inspection = inspectionRepository.inspect(seasonId);

        // 검사 도중 시간이 흘러 시즌 경계에 도달했어도 늦은 ACTIVE 전환을 금지합니다.
        Instant afterInspection = Instant.now(clock);
        if (afterInspection.isBefore(season.getStartAt())) {
            return new ActivationResult(Outcome.NOT_STARTED, inspection);
        }
        if (!afterInspection.isBefore(season.getEndAt())) {
            return new ActivationResult(Outcome.EXPIRED, inspection);
        }
        if (!inspection.complete()) {
            return new ActivationResult(Outcome.INCOMPLETE, inspection);
        }

        // 트랜잭션 Commit 직전에 상태를 바꾸며, 다른 초기화 실행은 시즌 잠금에서 대기합니다.
        boolean activated = season.activateAfterInitialization(afterInspection);
        if (!activated) {
            throw new IllegalStateException("SCHEDULED 시즌의 ACTIVE 전환이 반영되지 않았습니다.");
        }
        return new ActivationResult(Outcome.ACTIVATED, inspection);
    }

    /**
     * 시즌 활성화의 결정 결과를 표현합니다.
     * INCOMPLETE는 실패가 아니라 누락 그룹의 추가 초기화가 필요한 상태입니다.
     * EXPIRED는 이미 종료 구간이므로 이후 CLOSING 정상화 대상이 됩니다.
     */
    public enum Outcome {
        ACTIVATED,
        ALREADY_ACTIVE,
        INCOMPLETE,
        NOT_STARTED,
        EXPIRED,
        NOT_SCHEDULED
    }

    /**
     * 활성화 결과와 해당 시점의 DB 검사 요약을 보관합니다.
     * 상태·시간 경계에서 검사 자체를 생략했다면 inspection은 null입니다.
     * INCOMPLETE일 때는 어떤 기록이 누락됐는지 결과로 확인할 수 있습니다.
     */
    public record ActivationResult(
            Outcome outcome,
            SeasonActivationInspectionRepository.Inspection inspection
    ) {
    }
}
