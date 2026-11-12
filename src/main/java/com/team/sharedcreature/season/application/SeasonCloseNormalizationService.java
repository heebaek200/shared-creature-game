package com.team.sharedcreature.season.application;

import com.team.sharedcreature.admin.application.SchedulerJobRunner;
import com.team.sharedcreature.season.infrastructure.SeasonRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 누락된 시즌 종료 업무를 현재 DB 상태를 기준으로 찾아 재실행합니다.
 * 기동 이벤트·자정·5분 점검의 세 트리거가 같은 진입점을 사용합니다.
 * 오케스트레이터의 독립 트랜잭션 사이에 외부 트랜잭션을 열지 않습니다.
 */
@Slf4j
@Service
public class SeasonCloseNormalizationService {

    private final SeasonRepository seasonRepository;
    private final SeasonCloseJobService closeJobService;
    private final Clock clock;
    private final String ownerInstanceId;
    private final AtomicBoolean running = new AtomicBoolean(false);

    /**
     * 정상화 조회용 Repository와 종료 오케스트레이터를 주입받습니다.
     * 인스턴스별로 하나의 고유한 소유자 ID를 생성하여 재선점을 구분합니다.
     * 같은 서버에서는 세 종류의 트리거가 동일한 ID를 사용합니다.
     */
    public SeasonCloseNormalizationService(
            SeasonRepository seasonRepository,
            SeasonCloseJobService closeJobService,
            Clock clock
    ) {
        this.seasonRepository = seasonRepository;
        this.closeJobService = closeJobService;
        this.clock = clock;
        this.ownerInstanceId = "season-close-" + UUID.randomUUID();
    }

    /**
     * 종료 시각이 지난 시즌과 미완료 종료 이력을 한 번씩 정상화합니다.
     * 한 인스턴스에서 중첩 호출되면 두 번째 호출을 건너뜁니다.
     * 개별 시즌 오류는 기록하고 다음 시즌을 처리하며, 조회 오류는 전파합니다.
     *
     * @return 확인한 대상과 성공·건너뜀·실패 수
     */
    public NormalizationResult normalize() {
        // 공통 실행기가 이미 열려 있는 트랜잭션에서 실행되지 않도록 진입을 차단합니다.
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("시즌 종료 정상화는 트랜잭션 밖에서 호출해야 합니다.");
        }
        if (!running.compareAndSet(false, true)) {
            return new NormalizationResult(0, 0, 0, 0, true);
        }

        try {
            Instant now = Instant.now(clock);
            List<Long> seasonIds = seasonRepository.findIdsNeedingCloseNormalization(now);
            int succeeded = 0;
            int skipped = 0;
            int failed = 0;

            // 시작 시점에 발견한 시즌만 처리하며 목록 자체를 처리 중 다시 조회하지 않습니다.
            for (Long seasonId : seasonIds) {
                Objects.requireNonNull(seasonId, "seasonId");
                try {
                    SchedulerJobRunner.ExecutionResult result =
                            closeJobService.runSeasonClose(seasonId, ownerInstanceId);
                    if (result == SchedulerJobRunner.ExecutionResult.SUCCEEDED) {
                        succeeded++;
                    } else if (result == SchedulerJobRunner.ExecutionResult.SKIPPED) {
                        skipped++;
                    } else {
                        throw new IllegalStateException("알 수 없는 시즌 종료 실행 결과: " + result);
                    }
                } catch (RuntimeException error) {
                    // 한 시즌이 실패하더라도 뒤에 있는 다른 만료 시즌을 계속 검사합니다.
                    failed++;
                    log.error("시즌 종료 정상화 실패. seasonId={}", seasonId, error);
                }
            }

            NormalizationResult summary = new NormalizationResult(
                    seasonIds.size(), succeeded, skipped, failed, false
            );
            log.info("시즌 종료 정상화 완료. {}", summary);
            return summary;
        } finally {
            // 조회나 처리 중 예외가 발생해도 다음 기동·주기 점검이 실행될 수 있도록 해제합니다.
            running.set(false);
        }
    }

    /**
     * 현재 트리거에서 관찰한 시즌 종료 처리 결과입니다.
     * alreadyRunning은 같은 JVM에서 다른 트리거가 이미 실행 중이었다는 뜻입니다.
     * 실패 시즌은 다음 재점검에서 DB 상태를 다시 읽어 재시도합니다.
     *
     * @param examined 종료 처리 대상으로 조회한 시즌 수
     * @param succeeded 이번 실행에서 업무 및 성공 이력 확정에 성공한 수
     * @param skipped 다른 실행자 선점이나 이미 완료된 이력으로 건너뛴 수
     * @param failed 개별 시즌 종료에 실패한 수
     * @param alreadyRunning 같은 JVM에서 정상화가 실행 중이어서 건너뛴 여부
     */
    public record NormalizationResult(
            int examined,
            int succeeded,
            int skipped,
            int failed,
            boolean alreadyRunning
    ) {
    }
}
