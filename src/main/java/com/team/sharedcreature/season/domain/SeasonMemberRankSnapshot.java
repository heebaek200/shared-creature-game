package com.team.sharedcreature.season.domain;

import com.team.sharedcreature.auth.domain.User;
import com.team.sharedcreature.group.domain.Group;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * season_member_rank_snapshots 테이블의 상태와 이력을 보존하는 JPA 엔티티입니다.
 */
@Getter
@Entity
@Table(name = "season_member_rank_snapshots", uniqueConstraints = {
        @UniqueConstraint(name = "uq_member_rank_snapshots_group_user", columnNames = {"group_season_id", "user_id"}),
        @UniqueConstraint(name = "uq_member_rank_snapshots_group_rank", columnNames = {"group_season_id", "rank_number"})
})
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class SeasonMemberRankSnapshot {

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

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Column(name = "rank_number", nullable = false)
    private Integer rankNumber;

    @Column(name = "contribution_score", nullable = false)
    private Long contributionScore;

    @Column(name = "score_reached_at", nullable = false)
    private Instant scoreReachedAt;

    @Column(name = "participant_status", length = 30, nullable = false)
    private String participantStatus;

    @Column(name = "nickname_snapshot", length = 20, nullable = false)
    private String nicknameSnapshot;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;
}
