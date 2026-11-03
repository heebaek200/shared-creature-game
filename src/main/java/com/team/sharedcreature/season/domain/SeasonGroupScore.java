package com.team.sharedcreature.season.domain;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * season_group_scores 테이블의 상태와 이력을 보존하는 JPA 엔티티입니다.
 */
@Getter
@Entity
@Table(name = "season_group_scores", uniqueConstraints =
        @UniqueConstraint(name = "uq_season_group_scores_group_season", columnNames = "group_season_id"))
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class SeasonGroupScore {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "group_season_id", nullable = false)
    private GroupSeason groupSeason;

    @Column(name = "score", nullable = false)
    private Long score;

    @Column(name = "score_reached_at")
    private Instant scoreReachedAt;

    @Version
    @Column(name = "version", nullable = false)
    private Long version;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    private SeasonGroupScore(GroupSeason groupSeason, Instant now) {
        this.groupSeason = groupSeason;
        this.score = 0L;
        this.createdAt = now;
        this.updatedAt = now;
    }

    /** 신규 그룹 시즌의 0점 집계를 생성합니다. */
    public static SeasonGroupScore zero(GroupSeason groupSeason, Instant now) {
        return new SeasonGroupScore(groupSeason, now);
    }
}
