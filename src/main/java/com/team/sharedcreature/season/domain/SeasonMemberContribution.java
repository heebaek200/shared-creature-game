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

    /**
     * 그룹 시즌에 사용할 사용자의 기여도 행을 0점으로 생성합니다.
     *
     * @param groupSeason 기여도가 속할 그룹 시즌
     * @param user 기여도 소유 사용자
     * @param membership 현재 멤버십
     * @param now 생성 시각
     * @return 0점으로 초기화된 시즌 기여도
     */
    public static SeasonMemberContribution zero(
            GroupSeason groupSeason,
            User user,
            GroupMembership membership,
            Instant now
    ) {
        SeasonMemberContribution contribution = new SeasonMemberContribution();
        contribution.groupSeason = groupSeason;
        contribution.user = user;
        contribution.latestMembership = membership;
        contribution.contributionScore = 0L;
        contribution.scoreReachedAt = now;
        contribution.participantStatus = "ACTIVE";
        contribution.createdAt = now;
        contribution.updatedAt = now;
        return contribution;
    }

    /**
     * 퀘스트 완료로 얻은 점수를 개인 시즌 기여도에 더합니다.
     *
     * @param amount 더할 기여도 점수
     * @param membership 점수 반영 시점의 멤버십
     * @param now 점수 반영 시각
     */
    public void addContribution(long amount, GroupMembership membership, Instant now) {
        if (amount <= 0) {
            throw new IllegalArgumentException("기여도는 0이상이여야 합니다.");
        }

        this.contributionScore = Math.addExact(this.contributionScore, amount);
        this.latestMembership = membership;
        this.scoreReachedAt = now;
        this.updatedAt = now;
    }
}
