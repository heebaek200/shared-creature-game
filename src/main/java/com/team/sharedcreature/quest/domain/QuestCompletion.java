package com.team.sharedcreature.quest.domain;

import com.team.sharedcreature.auth.domain.User;
import com.team.sharedcreature.group.domain.GroupMembership;
import com.team.sharedcreature.season.domain.GroupSeason;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * quest_completions 테이블의 상태와 이력을 보존하는 JPA 엔티티입니다.
 */
@Getter
@Entity
@Table(name = "quest_completions", uniqueConstraints =
        @UniqueConstraint(name = "uq_quest_completions_user_assignment", columnNames = {"user_id", "quest_assignment_id"}), indexes = {
        @Index(name = "ix_quest_completions_group_completed", columnList = "group_season_id, completed_at"),
        @Index(name = "ix_quest_completions_user_completed", columnList = "user_id, completed_at")
})
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class QuestCompletion {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "quest_assignment_id", nullable = false)
    private QuestAssignment questAssignment;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "group_season_id", nullable = false)
    private GroupSeason groupSeason;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "membership_id", nullable = false)
    private GroupMembership membership;

    @Column(name = "completed_at", nullable = false)
    private Instant completedAt;

    @Column(name = "creature_total_experience_after")
    private Long creatureTotalExperienceAfter;

    @Column(name = "creature_growth_stage_after", length = 30)
    private String creatureGrowthStageAfter;

    @Column(name = "group_season_score_after")
    private Long groupSeasonScoreAfter;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    /**
     * 퀘스트 완료 기록을 생성합니다.
     * 완료한 사용자와 퀘스트 배정, 완료 당시의 그룹 시즌 및 멤버십을 기록합니다.
     * 완료 시각과 생성 시각은 동일한 서버 시각으로 저장합니다.
     *
     * @param user 완료한 사용자
     * @param assignment 완료한 퀘스트 배정
     * @param groupSeason 완료가 반영될 그룹 시즌
     * @param membership 완료 시점의 멤버십
     * @param now 완료 확정 시각
     * @return 생성된 퀘스트 완료 엔티티
     */
    public static QuestCompletion create(
            User user,
            QuestAssignment assignment,
            GroupSeason groupSeason,
            GroupMembership membership,
            Instant now
    ) {
        QuestCompletion completion = new QuestCompletion();
        completion.user = user;
        completion.questAssignment = assignment;
        completion.groupSeason = groupSeason;
        completion.membership = membership;
        completion.completedAt = now;
        completion.createdAt = now;
        return completion;
    }

    /** 중복 완료 응답을 재현할 수 있도록 최초 보상 확정 결과를 저장합니다. */
    public void recordResultSnapshot(
            long creatureTotalExperienceAfter,
            String creatureGrowthStageAfter,
            long groupSeasonScoreAfter
    ) {
        this.creatureTotalExperienceAfter = creatureTotalExperienceAfter;
        this.creatureGrowthStageAfter = creatureGrowthStageAfter;
        this.groupSeasonScoreAfter = groupSeasonScoreAfter;
    }
}
