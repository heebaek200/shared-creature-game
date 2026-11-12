package com.team.sharedcreature.season.infrastructure;

import com.team.sharedcreature.season.domain.GroupSeason;
import com.team.sharedcreature.season.domain.SeasonGroupRankSnapshot;
import com.team.sharedcreature.season.domain.SeasonGroupScore;
import com.team.sharedcreature.season.domain.SeasonMemberContribution;
import com.team.sharedcreature.season.domain.SeasonMemberRankSnapshot;
import com.team.sharedcreature.season.domain.SeasonRankingCalculator;
import jakarta.persistence.EntityManager;
import org.springframework.stereotype.Repository;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 종료 시즌의 원본 데이터와 랭킹 Snapshot을 JPQL로 조회하고 대조합니다.
 * 순위 계산은 Snapshot 저장 단계와 동일한 SeasonRankingCalculator를 재사용합니다.
 * 호출자는 같은 트랜잭션에서 시즌 행의 배타 잠금을 먼저 획득해야 합니다.
 */
@Repository
public class SeasonSnapshotCompletionRepository {

    private final EntityManager entityManager;

    /**
     * JPA 영속성 컨텍스트를 주입받습니다.
     * 검증용 데이터는 엔티티 중심의 JPQL로만 조회합니다.
     * 별도 트랜잭션을 열지 않고 호출자의 시즌 종료 트랜잭션에 참여합니다.
     */
    public SeasonSnapshotCompletionRepository(EntityManager entityManager) {
        this.entityManager = entityManager;
    }

    /**
     * ACTIVE에서 종료한 시즌의 그룹·개인 Snapshot을 전수 검사합니다.
     * 저장 서비스와 동일한 계산기로 기대 순위를 다시 계산합니다.
     * 원본 누락·Snapshot 누락·순위 불일치가 있으면 완료로 판정하지 않습니다.
     *
     * @param seasonId 종료 검증 대상 시즌 ID
     * @return 원본과 Snapshot의 개수 및 불일치 개수를 담은 결과
     */
    public Inspection inspectActiveClosing(Long seasonId) {
        Objects.requireNonNull(seasonId, "seasonId");

        // 모든 조회는 호출자가 잡아둔 시즌 행 배타 잠금과 같은 트랜잭션에서 수행됩니다.
        List<GroupSeason> groupSeasons = loadGroupSeasons(seasonId);
        List<SeasonGroupScore> scores = loadGroupScores(seasonId);
        List<SeasonGroupRankSnapshot> groupSnapshots = loadGroupSnapshots(seasonId);
        List<SeasonMemberContribution> contributions = loadContributions(seasonId);
        List<SeasonMemberRankSnapshot> memberSnapshots = loadMemberSnapshots(seasonId);

        // 원본/스냅샷을 동일한 그룹 시즌 ID로 연결합니다.
        Map<Long, SeasonGroupScore> scoreByGroupSeason = new HashMap<>();
        for (SeasonGroupScore score : scores) {
            scoreByGroupSeason.put(score.getGroupSeason().getId(), score);
        }
        Map<Long, SeasonGroupRankSnapshot> groupSnapByGroupSeason = new HashMap<>();
        for (SeasonGroupRankSnapshot snapshot : groupSnapshots) {
            groupSnapByGroupSeason.put(snapshot.getGroupSeason().getId(), snapshot);
        }

        long invalidSources = 0;
        List<SeasonRankingCalculator.GroupEntry> groupEntries = new ArrayList<>();
        for (GroupSeason groupSeason : groupSeasons) {
            SeasonGroupScore score = scoreByGroupSeason.get(groupSeason.getId());
            if (score == null) {
                continue; // 점수 행 누락은 개수 비교로 검출합니다.
            }

            // 0점은 생성 시각으로 대체하고, 양수의 도달 시각 누락은 오류입니다.
            if (score.getScore() == null || score.getScore() < 0
                    || (score.getScore() > 0 && score.getScoreReachedAt() == null)
                    || (score.getScore() == 0 && score.getScoreReachedAt() == null
                        && score.getCreatedAt() == null)) {
                invalidSources++;
                continue;
            }
            groupEntries.add(new SeasonRankingCalculator.GroupEntry(
                    groupSeason.getGroup().getId(),
                    score.getScore(),
                    score.getScoreReachedAt(),
                    score.getCreatedAt()
            ));
        }

        // 원본이 완전할 때만 계산기를 호출합니다. 누락·손상은 별도 검증값에 남습니다.
        Map<Long, SeasonRankingCalculator.GroupRank> groupRanks = new HashMap<>();
        if (groupEntries.size() == groupSeasons.size()) {
            for (SeasonRankingCalculator.GroupRank rank :
                    SeasonRankingCalculator.rankGroups(groupEntries)) {
                groupRanks.put(rank.groupId(), rank);
            }
        }

        long missingGroups = 0;
        long incorrectGroups = 0;
        for (GroupSeason groupSeason : groupSeasons) {
            SeasonGroupRankSnapshot snapshot = groupSnapByGroupSeason.get(groupSeason.getId());
            if (snapshot == null) {
                missingGroups++;
                continue;
            }

            // Snapshot 순위와 원본을 공통 계산기의 결과와 비교합니다.
            SeasonRankingCalculator.GroupRank expected =
                    groupRanks.get(groupSeason.getGroup().getId());
            if (expected != null && (
                    !Objects.equals(snapshot.getSeason().getId(), seasonId)
                    || !Objects.equals(snapshot.getGroup().getId(), expected.groupId())
                    || !Objects.equals(snapshot.getRankNumber(), expected.rankNumber())
                    || !Objects.equals(snapshot.getScore(), expected.score())
                    || !Objects.equals(snapshot.getScoreReachedAt(), expected.scoreReachedAt()))) {
                incorrectGroups++;
            }
        }

        // 개인 기여도는 그룹 시즌 단위로 독립적으로 순위를 계산합니다.
        Map<Long, List<SeasonMemberContribution>> membersByGroupSeason = new HashMap<>();
        for (SeasonMemberContribution contribution : contributions) {
            membersByGroupSeason.computeIfAbsent(
                    contribution.getGroupSeason().getId(), ignored -> new ArrayList<>()
            ).add(contribution);
        }
        Map<Long, Map<Long, SeasonMemberRankSnapshot>> snapsByGroupSeason = new HashMap<>();
        for (SeasonMemberRankSnapshot snapshot : memberSnapshots) {
            snapsByGroupSeason.computeIfAbsent(
                    snapshot.getGroupSeason().getId(), ignored -> new HashMap<>()
            ).put(snapshot.getUser().getId(), snapshot);
        }

        long missingMembers = 0;
        long incorrectMembers = 0;
        for (GroupSeason groupSeason : groupSeasons) {
            List<SeasonMemberContribution> members = membersByGroupSeason
                    .getOrDefault(groupSeason.getId(), List.of());
            Map<Long, SeasonMemberRankSnapshot> snaps = snapsByGroupSeason
                    .getOrDefault(groupSeason.getId(), Map.of());

            // 유효하지 않은 기여도가 있으면 전체 종료를 차단하고 순위 검증을 건너뜁니다.
            boolean invalidMemberSource = false;
            List<SeasonRankingCalculator.MemberEntry> memberEntries = new ArrayList<>();
            for (SeasonMemberContribution member : members) {
                if (member.getContributionScore() == null || member.getContributionScore() < 0
                        || member.getScoreReachedAt() == null) {
                    invalidSources++;
                    invalidMemberSource = true;
                    continue;
                }
                memberEntries.add(new SeasonRankingCalculator.MemberEntry(
                        member.getUser().getId(), member.getContributionScore(),
                        member.getScoreReachedAt()
                ));
            }

            Map<Long, SeasonRankingCalculator.MemberRank> expectedRanks = new HashMap<>();
            if (!invalidMemberSource) {
                for (SeasonRankingCalculator.MemberRank rank :
                        SeasonRankingCalculator.rankMembers(memberEntries)) {
                    expectedRanks.put(rank.userId(), rank);
                }
            }

            // 탈퇴 후 다른 그룹에서 재가입한 사용자는 각 그룹 시즌마다 별도로 검사합니다.
            for (SeasonMemberContribution member : members) {
                SeasonMemberRankSnapshot snapshot = snaps.get(member.getUser().getId());
                if (snapshot == null) {
                    missingMembers++;
                    continue;
                }
                SeasonRankingCalculator.MemberRank expected =
                        expectedRanks.get(member.getUser().getId());
                if (expected != null && (
                        !Objects.equals(snapshot.getSeason().getId(), seasonId)
                        || !Objects.equals(snapshot.getGroup().getId(), groupSeason.getGroup().getId())
                        || !Objects.equals(snapshot.getRankNumber(), expected.rankNumber())
                        || !Objects.equals(snapshot.getContributionScore(), expected.contributionScore())
                        || !Objects.equals(snapshot.getScoreReachedAt(), expected.scoreReachedAt()))) {
                    incorrectMembers++;
                }
            }
        }

        // 그룹 시즌/점수, 원본/스냅샷의 모든 개수가 일치해야 종료할 수 있습니다.
        return new Inspection(
                groupSeasons.size(), scores.size(), groupSnapshots.size(), missingGroups, incorrectGroups,
                contributions.size(), memberSnapshots.size(), missingMembers, incorrectMembers, invalidSources
        );
    }

    /**
     * 종료 대상 시즌에 저장된 그룹 Snapshot 개수를 JPQL로 조회합니다.
     * SCHEDULED에서 만료된 시즌은 반드시 0건이어야 합니다.
     * 같은 트랜잭션에서 조회만 수행합니다.
     */
    public long countGroupSnapshots(Long seasonId) {
        Objects.requireNonNull(seasonId, "seasonId");
        return entityManager.createQuery("""
                SELECT COUNT(snapshot)
                  FROM SeasonGroupRankSnapshot snapshot
                 WHERE snapshot.season.id = :seasonId
                """, Long.class)
                .setParameter("seasonId", seasonId)
                .getSingleResult();
    }

    /**
     * 종료 대상 시즌에 저장된 개인 Snapshot 개수를 JPQL로 조회합니다.
     * SCHEDULED에서 만료된 시즌은 반드시 0건이어야 합니다.
     * 별도 native SQL이나 JDBC를 호출하지 않습니다.
     */
    public long countMemberSnapshots(Long seasonId) {
        Objects.requireNonNull(seasonId, "seasonId");
        return entityManager.createQuery("""
                SELECT COUNT(snapshot)
                  FROM SeasonMemberRankSnapshot snapshot
                 WHERE snapshot.season.id = :seasonId
                """, Long.class)
                .setParameter("seasonId", seasonId)
                .getSingleResult();
    }

    /**
     * 시즌의 그룹 참여 행을 조회합니다.
     * 엔티티 이름과 연관관계를 사용하는 순수 JPQL입니다.
     * 시즌 배타 잠금은 호출한 최종 확정 Service가 획득합니다.
     */
    private List<GroupSeason> loadGroupSeasons(Long seasonId) {
        return entityManager.createQuery("""
                SELECT gs
                  FROM GroupSeason gs
                 WHERE gs.season.id = :seasonId
                """, GroupSeason.class)
                .setParameter("seasonId", seasonId)
                .getResultList();
    }

    /**
     * 시즌에 속한 그룹 점수 원본을 조회합니다.
     * groupSeason의 시즌 ID를 통해 범위를 제한합니다.
     * 별도의 테이블명이나 native SQL을 사용하지 않습니다.
     */
    private List<SeasonGroupScore> loadGroupScores(Long seasonId) {
        return entityManager.createQuery("""
                SELECT score
                  FROM SeasonGroupScore score
                 WHERE score.groupSeason.season.id = :seasonId
                """, SeasonGroupScore.class)
                .setParameter("seasonId", seasonId)
                .getResultList();
    }

    /**
     * 해당 시즌의 그룹 랭킹 Snapshot을 조회합니다.
     * 모든 Snapshot이 저장됐는지 비교하는 용도입니다.
     * 이미 저장된 값은 변경하지 않습니다.
     */
    private List<SeasonGroupRankSnapshot> loadGroupSnapshots(Long seasonId) {
        return entityManager.createQuery("""
                SELECT snapshot
                  FROM SeasonGroupRankSnapshot snapshot
                 WHERE snapshot.season.id = :seasonId
                """, SeasonGroupRankSnapshot.class)
                .setParameter("seasonId", seasonId)
                .getResultList();
    }

    /**
     * 해당 시즌의 개인 기여도 원본을 조회합니다.
     * 그룹 시즌별 랭킹 계산에 필요한 입력입니다.
     * 탈퇴하거나 이전 그룹에 남은 기여도도 포함합니다.
     */
    private List<SeasonMemberContribution> loadContributions(Long seasonId) {
        return entityManager.createQuery("""
                SELECT contribution
                  FROM SeasonMemberContribution contribution
                 WHERE contribution.groupSeason.season.id = :seasonId
                """, SeasonMemberContribution.class)
                .setParameter("seasonId", seasonId)
                .getResultList();
    }

    /**
     * 해당 시즌의 개인 랭킹 Snapshot을 조회합니다.
     * 사용자 ID와 그룹 시즌 ID로 원본에 대응시킵니다.
     * Snapshot에 저장된 닉네임 및 상태는 변경하지 않습니다.
     */
    private List<SeasonMemberRankSnapshot> loadMemberSnapshots(Long seasonId) {
        return entityManager.createQuery("""
                SELECT snapshot
                  FROM SeasonMemberRankSnapshot snapshot
                 WHERE snapshot.season.id = :seasonId
                """, SeasonMemberRankSnapshot.class)
                .setParameter("seasonId", seasonId)
                .getResultList();
    }

    /**
     * 원본·Snapshot 간 정합성 검사 결과입니다.
     * 기존 SeasonCloseFinalizationService의 호출 계약을 유지합니다.
     * 어느 하나라도 누락되거나 불일치하면 complete가 false입니다.
     */
    public record Inspection(
            long groupCount,
            long groupScoreCount,
            long groupSnapshotCount,
            long missingGroupSnapshots,
            long incorrectGroupSnapshots,
            long memberCount,
            long memberSnapshotCount,
            long missingMemberSnapshots,
            long incorrectMemberSnapshots,
            long invalidSourceRows
    ) {
        /**
         * 그룹·개인의 원본과 Snapshot이 누락 없이 저장됐는지 확인합니다.
         * 불일치 또는 소스 데이터 손상이 있다면 종료를 거절합니다.
         * 비교 결과는 기존 최종 확정 Service가 그대로 사용합니다.
         */
        public boolean complete() {
            return groupCount == groupScoreCount
                    && groupCount == groupSnapshotCount
                    && missingGroupSnapshots == 0
                    && incorrectGroupSnapshots == 0
                    && memberCount == memberSnapshotCount
                    && missingMemberSnapshots == 0
                    && incorrectMemberSnapshots == 0
                    && invalidSourceRows == 0;
        }
    }
}
