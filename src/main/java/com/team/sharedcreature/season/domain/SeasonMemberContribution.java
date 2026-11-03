package com.team.sharedcreature.season.domain;

import com.team.sharedcreature.auth.domain.User;
import com.team.sharedcreature.group.domain.GroupMembership;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * season_member_contributions 테이블의 상태와 이력을 보존하는 JPA 엔티티입니다.
 */
@Getter
@Entity
@Table(name = "season_member_contributions", uniqueConstraints =
        @UniqueConstraint(name = "uq_season_member_contributions_group_user", columnNames = {"group_season_id", "user_id"}), indexes =
        @Index(name = "ix_member_contributions_ranking", columnList = "group_season_id, contribution_score, score_reached_at, user_id"))
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class SeasonMemberContribution {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "group_season_id", nullable = false)
    private GroupSeason groupSeason;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "latest_membership_id", nullable = false)
    private GroupMembership latestMembership;

    @Column(name = "contribution_score", nullable = false)
    private Long contributionScore;

    @Column(name = "score_reached_at", nullable = false)
    private Instant scoreReachedAt;

    @Column(name = "participant_status", length = 30, nullable = false)
    private String participantStatus;

    @Version
    @Column(name = "version", nullable = false)
    private Long version;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;
}
