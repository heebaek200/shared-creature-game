package com.team.sharedcreature.quest.domain;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.Objects;

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

    /**
     * 기존 템플릿 버전의 배정 시점 데이터를 복제한 Snapshot을 생성합니다.
     * 완료 API는 원본 템플릿이 아닌 이 저장된 값으로 판정·보상을 계산합니다.
     * 기간/슬롯·기간/템플릿 UNIQUE로 중복 저장을 최종 차단합니다.
     */
    public static QuestAssignment capture(
            QuestPeriod period, QuestTemplate template, QuestTemplateVersion version,
            int slotNumber, Snapshot snapshot, Instant now
    ) {
        Objects.requireNonNull(period, "period");
        Objects.requireNonNull(template, "template");
        Objects.requireNonNull(version, "version");
        Objects.requireNonNull(snapshot, "snapshot");
        Objects.requireNonNull(now, "now");
        if (slotNumber < 1 || snapshot.creatureExperience() < 0 || snapshot.contributionScore() < 0) {
            throw new IllegalArgumentException("잘못된 슬롯 또는 보상입니다.");
        }

        // 원본 FK와 시점별 Snapshot 값을 동시에 보존합니다.
        QuestAssignment assignment = new QuestAssignment();
        assignment.questPeriod = period;
        assignment.questTemplate = template;
        assignment.questTemplateVersion = version;
        assignment.slotNumber = slotNumber;
        assignment.titleSnapshot = Objects.requireNonNull(snapshot.title(), "title");
        assignment.descriptionSnapshot = Objects.requireNonNull(snapshot.description(), "description");
        assignment.difficultySnapshot = Objects.requireNonNull(snapshot.difficulty(), "difficulty");
        assignment.verificationTypeSnapshot = Objects.requireNonNull(snapshot.verificationType(), "verificationType");
        assignment.verificationParametersSnapshot = Objects.requireNonNull(snapshot.verificationParameters(), "verificationParameters");
        assignment.creatureExperienceSnapshot = snapshot.creatureExperience();
        assignment.contributionScoreSnapshot = snapshot.contributionScore();
        assignment.createdAt = now;
        return assignment;
    }

    /**
     * 배정 당시에 고정할 공개 텍스트·검증 규칙·보상 값입니다.
     * 원본 템플릿 버전과 무관하게 불변으로 복사해 저장합니다.
     * 슬롯 순서는 별도 인자로 전달하여 UNIQUE 제약을 적용합니다.
     */
    public record Snapshot(
            String title, String description, String difficulty, String verificationType,
            String verificationParameters, long creatureExperience, long contributionScore
    ) {
    }
}
