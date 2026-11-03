package com.team.sharedcreature.season.domain;

import com.team.sharedcreature.group.domain.Group;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;

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
}
