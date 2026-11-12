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
    
    /**
     * 멤버십의 ACTIVE·LEFT·REMOVED 상태를 기여도에 반영합니다.
     * 실제 사용자와 그룹이 일치할 때만 변경하고 다른 그룹의 이력은 보존합니다.
     * 재가입 시에는 새 멤버십 참조를 사용하되 점수와 점수 도달 시각은 변경하지 않습니다.
     *
     * @param membership 동기화할 최신 멤버십
     * @param now 멤버십 상태 변경 시각
     */
    public void synchronizeMembership(GroupMembership membership, Instant now) {
        java.util.Objects.requireNonNull(membership, "membership");
        java.util.Objects.requireNonNull(now, "now");
        String nextStatus = membership.getStatus();
        // 참가 상태와 멤버십 상태를 동일하게 유지하며 외부 임의 값은 받지 않습니다.
        if (nextStatus == null || !java.util.Set.of("ACTIVE", "LEFT", "REMOVED").contains(nextStatus)) {
           throw new IllegalArgumentException("유효하지 않은 멤버십 상태: " + nextStatus);
        }
        if (!java.util.Objects.equals(user.getId(), membership.getUser().getId())
           || !java.util.Objects.equals(groupSeason.getGroup().getId(), membership.getGroup().getId())) {
           throw new IllegalArgumentException("기여도와 멤버십의 사용자·그룹이 다릅니다.");
        }
        if (java.util.Objects.equals(latestMembership.getId(), membership.getId())
               && java.util.Objects.equals(participantStatus, nextStatus)) {
           return;
        }

        // 이력 소유권만 교체하고 기여도 점수와 도달 시각을 수정하지 않습니다.
        latestMembership = membership;
        participantStatus = nextStatus;
        updatedAt = now;
   }

}
