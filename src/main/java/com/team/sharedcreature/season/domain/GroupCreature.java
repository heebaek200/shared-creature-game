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
}
