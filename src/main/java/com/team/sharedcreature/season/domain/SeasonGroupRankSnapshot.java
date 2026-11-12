package com.team.sharedcreature.season.domain;

import com.team.sharedcreature.group.domain.Group;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.Objects;

/**
 * season_group_rank_snapshots 테이블의 상태와 이력을 보존하는 JPA 엔티티입니다.
 */
@Getter
@Entity
@Table(name = "season_group_rank_snapshots", uniqueConstraints = {
        @UniqueConstraint(name = "uq_group_rank_snapshots_season_group", columnNames = {"season_id", "group_id"}),
        @UniqueConstraint(name = "uq_group_rank_snapshots_season_rank", columnNames = {"season_id", "rank_number"})
})
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class SeasonGroupRankSnapshot {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "season_id", nullable = false)
    private Season season;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "group_season_id", nullable = false)
    private GroupSeason groupSeason;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "group_id", nullable = false)
    private Group group;

    @Column(name = "rank_number", nullable = false)
    private Integer rankNumber;

    @Column(name = "score", nullable = false)
    private Long score;

    @Column(name = "score_reached_at", nullable = false)
    private Instant scoreReachedAt;

    @Column(name = "group_name_snapshot", length = 30, nullable = false)
    private String groupNameSnapshot;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    /**
     * 계산이 완료된 그룹 순위를 변경 불가능한 Snapshot 행으로 생성합니다.
     * 그룹 시즌의 소속 시즌 및 그룹 ID가 계산된 순위와 일치하는지 검증합니다.
     * 0점 대체 시각은 이미 SeasonRankingCalculator에서 계산된 값을 사용합니다.
     *
     * @param season 종료 중인 시즌
     * @param groupSeason 해당 그룹의 시즌 참여 정보
     * @param rank 계산된 그룹 순위
     * @param now 실제 Snapshot 생성 시각
     * @return 새 Snapshot 엔티티
     */
    public static SeasonGroupRankSnapshot capture(
            Season season,
            GroupSeason groupSeason,
            SeasonRankingCalculator.GroupRank rank,
            Instant now
    ) {
        Objects.requireNonNull(season, "season");
        Objects.requireNonNull(groupSeason, "groupSeason");
        Objects.requireNonNull(rank, "rank");
        Objects.requireNonNull(now, "now");

        // 잘못 연결된 시즌과 그룹의 Snapshot을 차단합니다.
        Group group = Objects.requireNonNull(groupSeason.getGroup(), "group");
        if (rank.rankNumber() < 1 || rank.score() < 0
                || !Objects.equals(groupSeason.getSeason().getId(), season.getId())
                || !Objects.equals(group.getId(), rank.groupId())) {
            throw new IllegalArgumentException("그룹 Snapshot의 시즌·그룹·순위가 일치하지 않습니다.");
        }

        // Snapshot에는 현재 표시명을 한 번만 복사하며 후속 갱신 메서드는 제공하지 않습니다.
        SeasonGroupRankSnapshot snapshot = new SeasonGroupRankSnapshot();
        snapshot.season = season;
        snapshot.groupSeason = groupSeason;
        snapshot.group = group;
        snapshot.rankNumber = rank.rankNumber();
        snapshot.score = rank.score();
        snapshot.scoreReachedAt = Objects.requireNonNull(rank.scoreReachedAt(), "scoreReachedAt");
        snapshot.groupNameSnapshot = Objects.requireNonNull(group.getName(), "groupName");
        snapshot.createdAt = now;
        return snapshot;
    }
}
