package com.team.sharedcreature.quest.domain;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * quest_template_versions 테이블의 상태와 이력을 보존하는 JPA 엔티티입니다.
 */
@Getter
@Entity
@Table(name = "quest_template_versions", uniqueConstraints =
        @UniqueConstraint(name = "uq_quest_template_versions_template_version", columnNames = {"quest_template_id", "version_number"}))
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class QuestTemplateVersion {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "quest_template_id", nullable = false)
    private QuestTemplate questTemplate;

    @Column(name = "version_number", nullable = false)
    private Integer versionNumber;

    @Column(name = "title", length = 100, nullable = false)
    private String title;

    @Column(name = "description", length = 500, nullable = false)
    private String description;

    @Column(name = "verification_type", length = 30, nullable = false)
    private String verificationType;

    @Column(name = "verification_parameters", columnDefinition = "JSON", nullable = false)
    private String verificationParameters;

    @Column(name = "creature_experience", nullable = false)
    private Long creatureExperience;

    @Column(name = "contribution_score", nullable = false)
    private Long contributionScore;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;
}
