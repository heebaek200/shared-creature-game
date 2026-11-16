package com.team.sharedcreature.season.application;

import com.team.sharedcreature.season.domain.Season;
import com.team.sharedcreature.season.infrastructure.SeasonRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 시즌 종료, 현재 시즌 생성, 그룹 초기화, 시즌 활성화를 순서대로 조정합니다.
 * 모든 실제 데이터 변경은 기존 REQUIRES_NEW 업무 서비스가 독립적으로 Commit합니다.
 * 기동·KST 자정·5분 재점검에서 같은 메서드를 호출하여 중단 지점을 복구합니다.
 */
@Slf4j
@Service
public class SeasonLifecycleNormalizationService {

    /**
     * 한 번의 정상화 호출에서 수행할 그룹 초기화 트랜잭션의 최대 수입니다.
     * 각 트랜잭션은 최대 100개 그룹을 처리하므로 호출당 최대 1,000개를 처리합니다.
     * 제한에 도달하면 다음 5분 점검에서 남은 그룹부터 이어서 진행합니다.
     */
    static final int MAX_INITIALIZATION_BATCHES = 10;

    private final SeasonCloseNormalizationService closeNormalizationService;
    private final SeasonCreationService seasonCreationService;
    private final SeasonGroupInitializationService initializationService;
    private final SeasonActivationService activationService;
    private final SeasonRepository seasonRepository;
    private final Clock clock;
    private final AtomicBoolean running = new AtomicBoolean(false);

    /**
     * 이미 테스트된 네 업무 서비스와 최종 상태 확인용 Repository를 연결합니다.
     * 종료·생성·초기화·활성화 사이에는 외부 트랜잭션을 열지 않습니다.
     * Scheduler의 실행권·시즌 행 잠금은 기존 서비스에서 각각 관리합니다.
     */
    public SeasonLifecycleNormalizationService(
            SeasonCloseNormalizationService closeNormalizationService,
            SeasonCreationService seasonCreationService,
            SeasonGroupInitializationService initializationService,
            SeasonActivationService activationService,
            SeasonRepository seasonRepository,
            Clock clock
    ) {
        this.closeNormalizationService = closeNormalizationService;
        this.seasonCreationService = seasonCreationService;
        this.initializationService = initializationService;
        this.activationService = activationService;
        this.seasonRepository = seasonRepository;
        this.clock = clock;
    }

    /**
     * 만료 시즌 종료부터 현재 시즌 활성화까지 한 번의 정상화를 수행합니다.
     * 미완료·경합·만료 상태에서는 잘못된 후속 작업을 하지 않고 다음 점검에 맡깁니다.
     * 이미 ACTIVE인 현재 시즌은 다시 초기화하지 않고 현재 유효성을 확인합니다.
     *
     * @return 정상화 상태, 처리 시즌 ID, 그룹 초기화 진행량 및 종료 처리 요약
     */
    public Result normalize() {
        // 트랜잭션이 걸린 상위 호출에서 REQUIRES_NEW들을 중첩하지 않도록 보호합니다.
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("시즌 전체 정상화는 기존 트랜잭션 밖에서 호출해야 합니다.");
        }
        if (!running.compareAndSet(false, true)) {
            return new Result(Outcome.ALREADY_RUNNING, null, 0, 0, null);
        }

        try {
            // 1. 이전 시즌의 Snapshot과 ENDED를 먼저 Commit합니다.
            SeasonCloseNormalizationService.NormalizationResult close = closeNormalizationService.normalize();
            if (close.alreadyRunning() || close.failed() > 0 || close.skipped() > 0) {
                return new Result(Outcome.WAITING_FOR_CLOSURE, null, 0, 0, close);
            }

            // 2. 최초 시즌 달력에 근거해 현재 구간 하나만 생성하거나 재사용합니다.
            SeasonCreationService.CreationResult creation =
                    seasonCreationService.createCurrentScheduledIfMissing();
            if (creation.outcome() == SeasonCreationService.Outcome.NOT_STARTED) {
                return new Result(Outcome.NOT_STARTED, creation.seasonId(), 0, 0, close);
            }
            if (creation.outcome() == SeasonCreationService.Outcome.WAITING_FOR_CLOSURE) {
                return new Result(Outcome.WAITING_FOR_CLOSURE, creation.seasonId(), 0, 0, close);
            }
            if (creation.outcome() != SeasonCreationService.Outcome.CREATED
                    && creation.outcome() != SeasonCreationService.Outcome.ALREADY_EXISTS) {
                throw new IllegalStateException("알 수 없는 시즌 생성 결과: " + creation.outcome());
            }

            Long seasonId = Objects.requireNonNull(creation.seasonId(), "creation.seasonId");
            int processedGroups = 0;
            int batches = 0;

            // 3. 각 배치는 독립 Commit. 마지막에 ACTIVE라면 변경 없이 활성화 확인 단계로 이동합니다.
            for (int i = 0; i < MAX_INITIALIZATION_BATCHES; i++) {
                SeasonGroupInitializationService.BatchResult batch =
                        initializationService.initializeNextBatch(seasonId);
                batches++;
                processedGroups = Math.addExact(processedGroups, batch.processedGroups());

                switch (batch.outcome()) {
                    case MORE_MAY_REMAIN -> {
                        // 정확히 100건 처리한 경우에도 남은 후보가 있는지 다음 배치에서 재조회합니다.
                    }
                    case COMPLETE -> {
                        return activateAndVerify(seasonId, batches, processedGroups, close);
                    }
                    case NOT_SCHEDULED -> {
                        // 현재 시즌이 이전 호출에서 ACTIVE로 전환됐는지 별도 확인합니다.
                        return activateAndVerify(seasonId, batches, processedGroups, close);
                    }
                    case NOT_STARTED -> {
                        return new Result(Outcome.NOT_STARTED, seasonId, batches, processedGroups, close);
                    }
                    case EXPIRED -> {
                        // 만료 구간에서 알을 새로 만들지 않습니다. 다음 종료 정상화에서 처리합니다.
                        return new Result(Outcome.EXPIRED, seasonId, batches, processedGroups, close);
                    }
                    default -> throw new IllegalStateException("알 수 없는 그룹 초기화 결과: " + batch.outcome());
                }
            }

            // 모든 배치를 한 번의 스케줄 주기에 몰아서 진행하지 않고 다음 트리거에 맡깁니다.
            return new Result(Outcome.MORE_GROUPS, seasonId, batches, processedGroups, close);
        } finally {
            running.set(false);
        }
    }

    /**
     * 모든 그룹 초기화 배치가 Commit된 다음에만 시즌 활성화를 시도합니다.
     * 기존 ACTIVE 시즌은 재사용하고, 활성화 직후에 실제 시각 범위를 재검증합니다.
     * 검사 실패 또는 시간 경과로 만료된 시즌을 준비 완료라고 보고하지 않습니다.
     */
    private Result activateAndVerify(
            Long seasonId,
            int batches,
            int processedGroups,
            SeasonCloseNormalizationService.NormalizationResult close
    ) {
        SeasonActivationService.ActivationResult activation =
                activationService.activateIfInitializationComplete(seasonId);
        return switch (activation.outcome()) {
            case ACTIVATED, ALREADY_ACTIVE -> {
                // 활성화 호출은 Commit된 뒤 반환됩니다. DB에서 다시 읽어 실제 현재 시즌인지 확인합니다.
                Season season = seasonRepository.findById(seasonId)
                        .orElseThrow(() -> new IllegalStateException("활성화된 시즌을 찾을 수 없습니다."));
                // 예상과 다른 상태가 저장되면 만료가 아니라 상태 경합으로 분리합니다.
                Outcome verified = !"ACTIVE".equals(season.getStatus())
                        ? Outcome.STATE_CHANGED
                        : season.isActiveAt(Instant.now(clock))
                                ? Outcome.ACTIVE : Outcome.EXPIRED;
                yield new Result(verified, seasonId, batches, processedGroups, close);
            }
            case INCOMPLETE -> new Result(Outcome.INCOMPLETE, seasonId, batches, processedGroups, close);
            case NOT_STARTED -> new Result(Outcome.NOT_STARTED, seasonId, batches, processedGroups, close);
            case EXPIRED -> new Result(Outcome.EXPIRED, seasonId, batches, processedGroups, close);
            case NOT_SCHEDULED -> new Result(Outcome.STATE_CHANGED, seasonId, batches, processedGroups, close);
        };
    }

    /**
     * 현재 정상화 호출에서 관찰한 상태입니다.
     * ACTIVE만 현재 시즌 준비를 확인한 상태이며 나머지는 이후 재점검 대상입니다.
     * 오류는 호출자에게 전달하고 스케줄 트리거에서 기록합니다.
     */
    public enum Outcome {
        ACTIVE,
        WAITING_FOR_CLOSURE,
        NOT_STARTED,
        MORE_GROUPS,
        INCOMPLETE,
        EXPIRED,
        STATE_CHANGED,
        ALREADY_RUNNING
    }

    /**
     * 한 번의 정상화 실행에서 Commit된 단계의 진행 상황을 나타냅니다.
     * batches와 processedGroups는 새 시즌의 그룹 초기화 결과만 합산합니다.
     * closeResult는 이전 시즌들의 종료 성공·실패·건너뜀을 진단할 때 사용합니다.
     */
    public record Result(
            Outcome outcome,
            Long seasonId,
            int batches,
            int processedGroups,
            SeasonCloseNormalizationService.NormalizationResult closeResult
    ) {
    }
}
