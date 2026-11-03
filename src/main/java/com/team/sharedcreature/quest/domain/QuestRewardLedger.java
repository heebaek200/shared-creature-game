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
}
