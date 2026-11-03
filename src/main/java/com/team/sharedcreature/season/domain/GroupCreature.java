package com.team.sharedcreature.season.domain;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * group_creatures 테이블의 상태와 이력을 보존하는 JPA 엔티티입니다.
 */
@Getter
@Entity
@Table(name = "group_creatures", uniqueConstraints =
        @UniqueConstraint(name = "uq_group_creatures_group_season", columnNames = "group_season_id"))
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class GroupCreature {

    public static final long GROWING_THRESHOLD = 45_000L;
    public static final long MATURE_THRESHOLD = 97_500L;
    public static final long COMPLETE_THRESHOLD = 150_000L;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "group_season_id", nullable = false)
    private GroupSeason groupSeason;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "species_id", nullable = true)
    private CreatureSpecies species;

    @Column(name = "selection_source", length = 30)
    private String selectionSource;

    @Column(name = "total_experience", nullable = false)
    private Long totalExperience;

    @Column(name = "growth_stage", length = 30, nullable = false)
    private String growthStage;

    @Column(name = "growth_stage_reached_at")
    private Instant growthStageReachedAt;

    @Column(name = "hatched_at")
    private Instant hatchedAt;

    @Column(name = "status", length = 30, nullable = false)
    private String status;

    @Version
    @Column(name = "version", nullable = false)
    private Long version;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    private GroupCreature(GroupSeason groupSeason, Instant now) {
        this.groupSeason = groupSeason;
        this.totalExperience = 0L;
        this.growthStage = "EGG";
        this.status = "ACTIVE";
        this.createdAt = now;
        this.updatedAt = now;
    }

    /** 새 그룹 시즌의 알 상태 공동 크리처를 생성합니다. */
    public static GroupCreature egg(GroupSeason groupSeason, Instant now) {
        return new GroupCreature(groupSeason, now);
    }

    /**
     * 유효한 퀘스트 경험치를 누적하고 부화한 크리처라면 최고 성장 단계로 전진시킵니다.
     *
     * @param amount 양수 경험치
     * @param now 보상 확정 시각
     */
    public void addExperience(long amount, Instant now) {
        if (amount <= 0) {
            throw new IllegalArgumentException("Experience amount must be positive.");
        }
        this.totalExperience = Math.addExact(totalExperience, amount);
        if (!"EGG".equals(growthStage)) {
            advanceTo(stageFor(totalExperience), now);
        }
        this.updatedAt = now;
    }

    /** 선택이 확정된 알을 누적 경험치에 맞는 최고 단계로 부화시킵니다. */
    public void hatch(CreatureSpecies species, String source, Instant now) {
        if (!"EGG".equals(growthStage)) {
            return;
        }
        this.species = species;
        this.selectionSource = source;
        this.hatchedAt = now;
        this.growthStage = stageFor(totalExperience);
        this.growthStageReachedAt = now;
        this.updatedAt = now;
    }

    /** 현재 경험치에 대응하는 부화 이후 성장 단계를 계산합니다. */
    public static String stageFor(long experience) {
        if (experience >= COMPLETE_THRESHOLD) {
            return "COMPLETE";
        }
        if (experience >= MATURE_THRESHOLD) {
            return "MATURE";
        }
        if (experience >= GROWING_THRESHOLD) {
            return "GROWING";
        }
        return "HATCHLING";
    }

    /** 응답 진행률에 사용하는 현재 단계 시작 경험치를 반환합니다. */
    public long currentStageThreshold() {
        return switch (growthStage) {
            case "GROWING" -> GROWING_THRESHOLD;
            case "MATURE" -> MATURE_THRESHOLD;
            case "COMPLETE" -> COMPLETE_THRESHOLD;
            default -> 0L;
        };
    }

    /** 응답 진행률에 사용하는 다음 단계 경험치를 반환합니다. */
    public Long nextStageThreshold() {
        return switch (growthStage) {
            case "EGG", "HATCHLING" -> GROWING_THRESHOLD;
            case "GROWING" -> MATURE_THRESHOLD;
            case "MATURE" -> COMPLETE_THRESHOLD;
            case "COMPLETE" -> null;
            default -> throw new IllegalStateException("Unknown growth stage: " + growthStage);
        };
    }

    private void advanceTo(String targetStage, Instant now) {
        if (stageOrder(targetStage) <= stageOrder(growthStage)) {
            return;
        }
        this.growthStage = targetStage;
        this.growthStageReachedAt = now;
    }

    private int stageOrder(String stage) {
        return switch (stage) {
            case "EGG" -> 0;
            case "HATCHLING" -> 1;
            case "GROWING" -> 2;
            case "MATURE" -> 3;
            case "COMPLETE" -> 4;
            default -> throw new IllegalStateException("Unknown growth stage: " + stage);
        };
    }
}
