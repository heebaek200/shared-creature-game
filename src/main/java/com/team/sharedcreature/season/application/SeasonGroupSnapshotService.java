package com.team.sharedcreature.season.application;

import com.team.sharedcreature.season.domain.GroupSeason;
import com.team.sharedcreature.season.domain.Season;
import com.team.sharedcreature.season.domain.SeasonGroupRankSnapshot;
import com.team.sharedcreature.season.domain.SeasonGroupScore;
import com.team.sharedcreature.season.domain.SeasonRankingCalculator;
import com.team.sharedcreature.season.infrastructure.GroupSeasonRepository;
import com.team.sharedcreature.season.infrastructure.SeasonGroupRankSnapshotRepository;
import com.team.sharedcreature.season.infrastructure.SeasonGroupScoreRepository;
import com.team.sharedcreature.season.infrastructure.SeasonRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * CLOSING 시즌의 그룹 랭킹 Snapshot을 100건씩 저장합니다.
 * 시즌 행 배타 잠금으로 다중 인스턴스의 Snapshot 처리를 직렬화합니다.
 * 이미 기록된 Snapshot은 수정하지 않고 정합성을 검증한 뒤 누락 건만 생성합니다.
 * 하나의 호출이 처리하는 최대 Snapshot 수는 100건입니다.
 */
@Service
public class SeasonGroupSnapshotService {

    private static final int BATCH_SIZE = 100;

    private final SeasonRepository seasonRepository;
    private final GroupSeasonRepository groupSeasonRepository;
    private final SeasonGroupScoreRepository scoreRepository;
    private final SeasonGroupRankSnapshotRepository snapshotRepository;
    private final Clock clock;

    public SeasonGroupSnapshotService(
            SeasonRepository seasonRepository,
            GroupSeasonRepository groupSeasonRepository,
            SeasonGroupScoreRepository scoreRepository,
            SeasonGroupRankSnapshotRepository snapshotRepository,
            Clock clock
    ) {
        this.seasonRepository = seasonRepository;
        this.groupSeasonRepository = groupSeasonRepository;
        this.scoreRepository = scoreRepository;
        this.snapshotRepository = snapshotRepository;
        this.clock = clock;
    }

    /**
     * CLOSING 시즌의 누락된 그룹 랭킹 Snapshot을 최대 100건 생성합니다.
     * ACTIVE에서 만료된 시즌만 랭킹을 생성하며 SCHEDULED는 건너뜁니다.
     * 모든 저장은 독립 트랜잭션에서 수행되어 실패 시 이번 배치만 Rollback됩니다.
     *
     * @param seasonId Snapshot 대상 시즌 ID
     * @return 이번 배치 결과와 아직 저장하지 않은 그룹 수
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Progress writeNextBatch(Long seasonId) {
        Objects.requireNonNull(seasonId, "seasonId");

        // 종료 상태와 다른 작성자를 직렬화하기 위해 시즌 행을 가장 먼저 잠급니다.
        Season season = seasonRepository.findForUpdateById(seasonId)
                .orElseThrow(() -> new IllegalStateException("시즌을 찾을 수 없습니다. seasonId=" + seasonId));
        if (!"CLOSING".equals(season.getStatus())) {
            throw new IllegalStateException("CLOSING 시즌만 Snapshot을 생성할 수 있습니다.");
        }
        if (!season.requiresRankingSnapshotOnClosing()) {
            return new Progress(0, 0, 0);
        }

        // 그룹 시즌과 해당 점수를 별도 조회하여 초기화 누락을 감지합니다.
        List<GroupSeason> groupSeasons = groupSeasonRepository.findAllForSeasonSnapshot(seasonId);
        List<SeasonGroupScore> scores = scoreRepository.findAllForSeasonSnapshot(seasonId);

        Map<Long, SeasonGroupScore> scoreByGroupSeasonId = new HashMap<>();
        for (SeasonGroupScore score : scores) {
            Long groupSeasonId = score.getGroupSeason().getId();
            if (scoreByGroupSeasonId.putIfAbsent(groupSeasonId, score) != null) {
                throw new IllegalStateException("그룹 시즌 점수가 중복됐습니다. groupSeasonId=%d".formatted(groupSeasonId));
            }
        }

        if (groupSeasons.size() != scoreByGroupSeasonId.size()) {
            throw new IllegalStateException("그룹 시즌 수와 점수 행 수가 다릅니다.");
        }

        Map<Long, GroupSeason> groupSeasonByGroupId = new HashMap<>();
        List<SeasonRankingCalculator.GroupEntry> entries = new java.util.ArrayList<>();
        for (GroupSeason groupSeason : groupSeasons) {
            Long groupId = groupSeason.getGroup().getId();
            if (groupSeasonByGroupId.putIfAbsent(groupId, groupSeason) != null) {
                throw new IllegalStateException("그룹 시즌이 중복됐습니다. groupId=%d".formatted(groupId));
            }
            SeasonGroupScore score = scoreByGroupSeasonId.get(groupSeason.getId());
            if (score == null) {
                throw new IllegalStateException("그룹 시즌 점수가 누락됐습니다. groupSeasonId=%d".formatted(groupSeason.getId()));
            }

            // 0점 그룹의 NULL 도달 시각은 Calculator에서 점수 행 생성 시각으로 보완합니다.
            entries.add(new SeasonRankingCalculator.GroupEntry(
                    groupId,
                    score.getScore(),
                    score.getScoreReachedAt(),
                    score.getCreatedAt()
            ));
        }
        List<SeasonRankingCalculator.GroupRank> ranks = SeasonRankingCalculator.rankGroups(entries);

        // 이전 배치에서 확정한 Snapshot을 읽고 순위가 일관적인지 검사합니다.
        List<SeasonGroupRankSnapshot> existing = snapshotRepository.findAllBySeasonId(seasonId);
        Map<Long, SeasonGroupRankSnapshot> existingByGroupId = new HashMap<>();
        Set<Integer> existingRankNumbers = new HashSet<>();
        for (SeasonGroupRankSnapshot snapshot : existing) {
            Long groupId = snapshot.getGroup().getId();
            if (existingByGroupId.putIfAbsent(groupId, snapshot) != null
                    || !existingRankNumbers.add(snapshot.getRankNumber())
                    || !groupSeasonByGroupId.containsKey(groupId)) {
                throw new IllegalStateException("저장된 그룹 Snapshot의 식별자 또는 순위가 잘못됐습니다.");
            }
        }

        int inserted = 0;
        Instant now = Instant.now(clock);
        for (SeasonRankingCalculator.GroupRank rank : ranks) {
            GroupSeason groupSeason = groupSeasonByGroupId.get(rank.groupId());
            SeasonGroupRankSnapshot snapshot = existingByGroupId.get(rank.groupId());
            if (snapshot != null) {
                // 재시도 때 이미 저장한 Snapshot은 수정하거나 생성 시각을 덮어쓰지 않습니다.
                if (!Objects.equals(snapshot.getGroupSeason().getId(), groupSeason.getId())
                        || snapshot.getRankNumber() != rank.rankNumber()
                        || snapshot.getScore() != rank.score()
                        || !Objects.equals(snapshot.getScoreReachedAt(), rank.scoreReachedAt())) {
                    throw new IllegalStateException("이미 저장된 그룹 Snapshot과 현재 랭킹이 일치하지 않습니다.");
                }
                continue;
            }

            if (inserted >= BATCH_SIZE) {
                break;
            }

            snapshotRepository.save(SeasonGroupRankSnapshot.capture(season, groupSeason, rank, now));
            inserted++;
        }

        // Snapshot 저장의 실제 Commit은 이 메서드가 정상적으로 종료된 다음 발생합니다.
        return new Progress(ranks.size(), inserted, ranks.size() - existing.size() - inserted);
    }

    /**
     * 시즌 그룹 Snapshot 저장 한 번의 진행 상황을 표현합니다.
     * remaining이 0이면 그룹 랭킹 저장은 완료된 상태입니다.
     * 개인 Snapshot과 시즌 ENDED 확정은 별도 단계에서 수행합니다.
     */
    public record Progress(int total, int inserted, int remaining) {
    }
}
