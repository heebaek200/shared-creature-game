package com.team.sharedcreature.season.domain;

import com.team.sharedcreature.auth.domain.User;
import com.team.sharedcreature.auth.domain.UserProfile;
import com.team.sharedcreature.group.domain.Group;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.Objects;
import java.util.Set;

/**
 * season_member_rank_snapshots 테이블의 상태와 이력을 보존하는 JPA 엔티티입니다.
 * 그룹 시즌과 사용자 조합당 하나의 종료 랭킹만 기록합니다.
 * 저장 후에는 수정 메서드를 제공하지 않아 Snapshot을 불변으로 유지합니다.
 */
@Getter
@Entity
@Table(name = "season_member_rank_snapshots", uniqueConstraints = {
        @UniqueConstraint(name = "uq_member_rank_snapshots_group_user", columnNames = {"group_season_id", "user_id"}),
        @UniqueConstraint(name = "uq_member_rank_snapshots_group_rank", columnNames = {"group_season_id", "rank_number"})
})
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class SeasonMemberRankSnapshot {

    private static final Set<String> PARTICIPANT_STATUSES = Set.of("ACTIVE", "LEFT", "REMOVED");

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

    /**
     * 계산된 개인 순위를 종료 시점의 불변 Snapshot으로 생성합니다.
     * 시즌·그룹 시즌·사용자와 원본 기여도가 순위 계산 결과에 일치하는지 검증합니다.
     * 닉네임과 참가 상태는 생성 시 한 번만 복사하고 이후 변경하지 않습니다.
     *
     * @param season 종료 중인 시즌
     * @param groupSeason 기여도가 소속된 그룹 시즌
     * @param contribution 원본 개인 기여도
     * @param profile 닉네임을 저장할 사용자 프로필
     * @param rank 계산한 그룹 시즌 내 순위
     * @param now Snapshot 생성 시각
     * @return 새 Snapshot 엔티티
     */
    public static SeasonMemberRankSnapshot capture(
            Season season,
            GroupSeason groupSeason,
            SeasonMemberContribution contribution,
            UserProfile profile,
            SeasonRankingCalculator.MemberRank rank,
            Instant now
    ) {
        Objects.requireNonNull(season, "season");
        Objects.requireNonNull(groupSeason, "groupSeason");
        Objects.requireNonNull(contribution, "contribution");
        Objects.requireNonNull(profile, "profile");
        Objects.requireNonNull(rank, "rank");
        Objects.requireNonNull(now, "now");

        // 서로 다른 시즌·그룹·사용자 데이터가 혼합되면 Snapshot 생성을 거부합니다.
        Group group = Objects.requireNonNull(groupSeason.getGroup(), "group");
        User user = Objects.requireNonNull(contribution.getUser(), "user");
        if (rank.rankNumber() < 1 || rank.contributionScore() < 0
                || !Objects.equals(groupSeason.getSeason().getId(), season.getId())
                || !Objects.equals(contribution.getGroupSeason().getId(), groupSeason.getId())
                || !Objects.equals(user.getId(), rank.userId())
                || !Objects.equals(profile.getUser().getId(), user.getId())
                || !Objects.equals(contribution.getContributionScore(), rank.contributionScore())
                || !Objects.equals(contribution.getScoreReachedAt(), rank.scoreReachedAt())) {
            throw new IllegalArgumentException("개인 Snapshot의 시즌·사용자·기여도·순위가 일치하지 않습니다.");
        }

        // 원본의 참가 상태와 표시 닉네임을 정확히 한 번 기록합니다.
        String status = contribution.getParticipantStatus();
        String nickname = Objects.requireNonNull(profile.getNickname(), "nickname");
        if (status == null || !PARTICIPANT_STATUSES.contains(status)
                || nickname.isBlank() || nickname.length() > 20) {
            throw new IllegalArgumentException("개인 Snapshot의 참가 상태 또는 닉네임이 유효하지 않습니다.");
        }

        SeasonMemberRankSnapshot snapshot = new SeasonMemberRankSnapshot();
        snapshot.season = season;
        snapshot.groupSeason = groupSeason;
        snapshot.group = group;
        snapshot.user = user;
        snapshot.rankNumber = rank.rankNumber();
        snapshot.contributionScore = rank.contributionScore();
        snapshot.scoreReachedAt = Objects.requireNonNull(rank.scoreReachedAt(), "scoreReachedAt");
        snapshot.participantStatus = status;
        snapshot.nicknameSnapshot = nickname;
        snapshot.createdAt = now;
        return snapshot;
    }
}
