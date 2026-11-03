package com.team.sharedcreature.season.domain;

import com.team.sharedcreature.quest.domain.QuestCompletion;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * creature_experience_ledgers 테이블의 상태와 이력을 보존하는 JPA 엔티티입니다.
 */
@Getter
@Entity
@Table(name = "creature_experience_ledgers")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class CreatureExperienceLedger {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "group_creature_id", nullable = false)
    private GroupCreature groupCreature;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "quest_completion_id", nullable = false)
    private QuestCompletion questCompletion;

    @Column(name = "experience_amount", nullable = false)
    private Long experienceAmount;

    @Column(name = "balance_after", nullable = false)
    private Long balanceAfter;

    @Column(name = "occurred_at", nullable = false)
    private Instant occurredAt;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;
}
