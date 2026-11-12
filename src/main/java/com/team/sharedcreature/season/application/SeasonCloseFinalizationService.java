package com.team.sharedcreature.season.application;

import com.team.sharedcreature.season.domain.Season;
import com.team.sharedcreature.season.infrastructure.SeasonRepository;
import com.team.sharedcreature.season.infrastructure.SeasonSnapshotCompletionRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.Objects;

/**
 * 종료 시즌의 랭킹 Snapshot 완료를 확인한 뒤 ENDED로 확정합니다.
 * 배치 서비스와 독립된 트랜잭션으로 시즌 행을 먼저 배타 잠금합니다.
 * 아직 누락된 기록이 있거나 저장 내용이 불일치하면 종료하지 않습니다.
 */
@Slf4j
@Service
public class SeasonCloseFinalizationService {

    private final SeasonRepository seasonRepository;
    private final SeasonSnapshotCompletionRepository completionRepository;
    private final Clock clock;

    /**
     * 시즌 배타 잠금, Snapshot 정합성 검사, 실제 시각을 주입받습니다.
     * 검사와 ENDED 전환이 하나의 독립된 트랜잭션 안에서 일어나야 합니다.
     * 그룹·개인 Snapshot 저장 서비스는 여기에서 호출하지 않습니다.
     */
    public SeasonCloseFinalizationService(
            SeasonRepository seasonRepository,
            SeasonSnapshotCompletionRepository completionRepository,
            Clock clock
    ) {
        this.seasonRepository = seasonRepository;
        this.completionRepository = completionRepository;
        this.clock = clock;
    }

    /**
     * 저장된 Snapshot이 완성된 CLOSING 시즌을 ENDED로 확정합니다.
     * ACTIVE 출처는 그룹·개인 순위의 전체 정합성을 확인해야 합니다.
     * SCHEDULED 출처는 랭킹 Snapshot이 없을 때에만 종료할 수 있습니다.
     *
     * @param seasonId 종료할 시즌 ID
     * @return 새롭게 ENDED로 전환되면 true, 이미 ENDED이면 false
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean finishIfSnapshotsComplete(Long seasonId) {
        Objects.requireNonNull(seasonId, "seasonId");

        // 다른 Snapshot 배치가 실행 중이라면 잠금이 해제된 뒤 검사합니다.
        Season season = seasonRepository.findForUpdateById(seasonId)
                .orElseThrow(() -> new IllegalStateException(
                        "최종 종료 대상 시즌을 찾을 수 없습니다. seasonId=" + seasonId
                ));

        // 반복 정상화에서는 저장 상태와 종료 시각을 변경하지 않습니다.
        if ("ENDED".equals(season.getStatus())) {
            return false;
        }
        if (!"CLOSING".equals(season.getStatus())) {
            throw new IllegalStateException("CLOSING 시즌만 ENDED로 확정할 수 있습니다.");
        }

        // 종료 직전 상태는 DB에 저장돼 있으므로 재기동 이후에도 구분할 수 있습니다.
        if (season.requiresRankingSnapshotOnClosing()) {
            SeasonSnapshotCompletionRepository.Inspection inspection =
                    completionRepository.inspectActiveClosing(seasonId);
            if (!inspection.complete()) {
                throw new IllegalStateException(
                        "랭킹 Snapshot이 누락됐거나 원본과 일치하지 않습니다. " + inspection
                );
            }
        } else {
            // 활성화되지 못한 시즌에는 그룹/개인 랭킹을 소급 생성하지 않습니다.
            long groups = completionRepository.countGroupSnapshots(seasonId);
            long members = completionRepository.countMemberSnapshots(seasonId);
            if (groups != 0 || members != 0) {
                throw new IllegalStateException(
                        "SCHEDULED에서 만료된 시즌에 랭킹 Snapshot이 존재합니다. "
                                + "groups=" + groups + ", members=" + members
                );
            }
        }

        // 정합성 검사를 통과한 뒤에만 JPA Dirty Checking으로 ENDED를 Commit합니다.
        return season.finishClosingAfterSnapshot(Instant.now(clock));
    }
}
