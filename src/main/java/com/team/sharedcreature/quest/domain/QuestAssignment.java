package com.team.sharedcreature.quest.domain;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * quest_assignments 테이블의 상태와 이력을 보존하는 JPA 엔티티입니다.
 */
@Getter
@Entity
@Table(name = "quest_assignments", uniqueConstraints = {
        @UniqueConstraint(name = "uq_quest_assignments_period_slot", columnNames = {"quest_period_id", "slot_number"}),
        @UniqueConstraint(name = "uq_quest_assignments_period_template", columnNames = {"quest_period_id", "quest_template_id"})
})
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class QuestAssignment {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "quest_period_id", nullable = false)
    private QuestPeriod questPeriod;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "quest_template_id", nullable = false)
    private QuestTemplate questTemplate;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "quest_template_version_id", nullable = false)
    private QuestTemplateVersion questTemplateVersion;

    @Column(name = "slot_number", nullable = false)
    private Integer slotNumber;

    @Column(name = "title_snapshot", length = 100, nullable = false)
    private String titleSnapshot;

    @Column(name = "description_snapshot", length = 500, nullable = false)
    private String descriptionSnapshot;

    @Column(name = "difficulty_snapshot", length = 30, nullable = false)
    private String difficultySnapshot;

    @Column(name = "verification_type_snapshot", length = 30, nullable = false)
    private String verificationTypeSnapshot;

    @Column(name = "verification_parameters_snapshot", columnDefinition = "JSON", nullable = false)
    private String verificationParametersSnapshot;

    @Column(name = "creature_experience_snapshot", nullable = false)
    private Long creatureExperienceSnapshot;

    @Column(name = "contribution_score_snapshot", nullable = false)
    private Long contributionScoreSnapshot;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;
}
