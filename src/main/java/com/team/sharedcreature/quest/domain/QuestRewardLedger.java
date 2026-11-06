package com.team.sharedcreature.quest.domain;

import com.team.sharedcreature.season.domain.GroupCreature;
import com.team.sharedcreature.season.domain.SeasonGroupScore;
import com.team.sharedcreature.season.domain.SeasonMemberContribution;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * quest_reward_ledgers 테이블의 상태와 이력을 보존하는 JPA 엔티티입니다.
 */
@Getter
@Entity
@Table(name = "quest_reward_ledgers", uniqueConstraints =
        @UniqueConstraint(name = "uq_quest_reward_ledgers_completion_type", columnNames = {"quest_completion_id", "reward_type"}))
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class QuestRewardLedger {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "quest_completion_id", nullable = false)
    private QuestCompletion questCompletion;

    @Column(name = "reward_type", length = 30, nullable = false)
    private String rewardType;

    @Column(name = "amount", nullable = false)
    private Long amount;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "group_creature_id", nullable = true)
    private GroupCreature groupCreature;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "member_contribution_id", nullable = true)
    private SeasonMemberContribution memberContribution;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "group_score_id", nullable = true)
    private SeasonGroupScore groupScore;

    @Column(name = "applied_at", nullable = false)
    private Instant appliedAt;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    /**
     * 완료 기록에 연결된 보상 생성합니다.
     *
     * @param completion 보상을 발생시킨 완료 기록
     * @param rewardType 보상 종류
     * @param amount 지급한 값
     * @param groupCreature 경험치 보상이면 대상 크리처, 아니면 null
     * @param memberContribution 개인 기여도 보상이면 대상 집계, 아니면 null
     * @param groupScore 그룹 점수 보상이면 대상 집계, 아니면 null
     * @param now 보상 확정 시각
     * @return 생성할 보상
     */
    public static QuestRewardLedger create(
            QuestCompletion completion,
            String rewardType,
            long amount,
            GroupCreature groupCreature,
            SeasonMemberContribution memberContribution,
            SeasonGroupScore groupScore,
            Instant now
    ) {
        QuestRewardLedger ledger = new QuestRewardLedger();
        ledger.questCompletion = completion;
        ledger.rewardType = rewardType;
        ledger.amount = amount;
        ledger.groupCreature = groupCreature;
        ledger.memberContribution = memberContribution;
        ledger.groupScore = groupScore;
        ledger.appliedAt = now;
        ledger.createdAt = now;
        return ledger;
    }
}
