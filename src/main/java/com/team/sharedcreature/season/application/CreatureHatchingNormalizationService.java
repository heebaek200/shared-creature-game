package com.team.sharedcreature.season.application;

import com.team.sharedcreature.season.infrastructure.GroupSeasonRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 서버 기동, 자정, 5분 점검에서 부화 가능한 그룹 시즌을 찾아 처리한다.
 * 실제 부화는 기존 CreatureSelectionService의 독립 트랜잭션에 위임한다.
 * 매 회차 최대 1,000건을 검사하며 실패한 대상은 다음 점검에서 재시도한다.
 */
@Slf4j
@Service
public class CreatureHatchingNormalizationService {

    public static final int BATCH_SIZE = 100;
    public static final int MAX_BATCHES = 10;

    private final GroupSeasonRepository groupSeasonRepository;
    private final CreatureSelectionService creatureSelectionService;
    private final Clock clock;
    private final AtomicBoolean running = new AtomicBoolean(false);

    public CreatureHatchingNormalizationService(
            GroupSeasonRepository groupSeasonRepository,
            CreatureSelectionService creatureSelectionService,
            Clock clock
    ) {
        this.groupSeasonRepository = groupSeasonRepository;
        this.creatureSelectionService = creatureSelectionService;
        this.clock = clock;
    }

    /**
     * 부화 점검 호출의 관찰 결과를 표시하는 레코드.
     * inspected는 다른 실행자가 먼저 부화시켜 건너뛴 대상도 포함한다.
     * failed는 다음 주기 재시도 대상이며, 이미 Commit한 부화는 되돌리지 않는다.
     */
    public record Result(
            int inspected,
            int hatched,
            int failed,
            boolean moreMayRemain,
            boolean alreadyRunning
    ) {}

    /**
     * ACTIVE 시즌의 선택 기한이 지난 그룹 시즌을 ID 기준으로 순차점검한다.
     * 최대 10개 배치를 순회하여 각 부화는 별도 트랜잭션으로 Commit된다.
     * 실패한 한 그룹은 기록하고 다음 그룹으로 진행하며 다음 점검에서 재시도한다.
     */
    public Result normalizeForActiveSeason(Long seasonId) {
        Objects.requireNonNull(seasonId, "seasonId");

        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("부화 정상화는 기존 트랜잭션 밖에서 호출해야 합니다.");
        }
        if (! running.compareAndSet(false, true)) {
            return new Result(0, 0, 0, false, true);
        }

        try {
            // 후보 조회는 잠금없는 읽기이며, 상태 전이 직전에 다시 기한을 확인한다.
            Instant scanAt = Instant.now(clock);
            Long afterId  = 0L;
            int inspected = 0;
            int hatched   = 0;
            int failed    = 0;

            for (int batch = 0 ; batch < MAX_BATCHES ; batch++) {
                List<Long> ids = groupSeasonRepository.findDueForHatching(
                        seasonId, scanAt, afterId, PageRequest.of(0, BATCH_SIZE)
                );
                if (ids.isEmpty()) {
                    return new Result(inspected, hatched, failed, false, false);
                }

                // 키셋 페이지를 사용하므로, 성공 실패와 관계없이 다음 ID로 이동한다.
                for (Long groupSeasonId : ids) {
                    if (groupSeasonId == null || groupSeasonId <= afterId) {
                        throw new IllegalStateException("부화 후보 ID가 오름차순이 아닙니다.");
                    }
                    afterId = groupSeasonId;
                    inspected++;

                    try {
                        // 실제 부화에 성공해 결과가 존재할 때만 성공 건수를 증가시킵니다.
                        // 이미 부화했거나 부화 대상이 아니면 Optional.empty()가 반환됩니다.
                        if (creatureSelectionService.hatchIfDue(groupSeasonId).isPresent()) {
                            hatched++;
                        }
                    } catch (RuntimeException e) {
                        failed++;
                        log.error("그룹 크리처 부화 실패. groupSeasonId={}", groupSeasonId, e);
                    }
                }

                if (ids.size() < BATCH_SIZE) {
                    return new Result(inspected, hatched, failed, false, false);
                }
            }

            // 최대 처리 수에 도달한 경우, 남은 후보는 다음 5분 트리거에서 재개한다.
            boolean moreMayRemain = !groupSeasonRepository.findDueForHatching(
                    seasonId, scanAt, afterId, PageRequest.of(0, 1)
            ).isEmpty();
            return new Result(inspected, hatched, failed, moreMayRemain, false);
        } finally {
            running.set(false);
        }

    }


}
