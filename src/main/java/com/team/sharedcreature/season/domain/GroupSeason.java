package com.team.sharedcreature.season.domain;

import com.team.sharedcreature.auth.domain.User;
import com.team.sharedcreature.group.domain.Group;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.Objects;

/**
 * group_seasons 테이블의 상태와 이력을 보존하는 JPA 엔티티입니다.
 */
@Getter
@Entity
@Table(name = "group_seasons", uniqueConstraints =
        @UniqueConstraint(name = "uq_group_seasons_group_season", columnNames = {"group_id", "season_id"}), indexes =
        @Index(name = "ix_group_seasons_status_deadline", columnList = "status, selection_deadline_at"))
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class GroupSeason {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "group_id", nullable = false)
    private Group group;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "season_id", nullable = false)
    private Season season;

    @Column(name = "selection_deadline_at", nullable = false)
    private Instant selectionDeadlineAt;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "selected_species_id", nullable = true)
    private CreatureSpecies selectedSpecies;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "selected_by_user_id", nullable = true)
    private User selectedByUser;

    @Column(name = "selection_source", length = 30, nullable = true)
    private String selectionSource;

    @Column(name = "selected_at", nullable = true)
    private Instant selectedAt;

    @Column(name = "status", length = 30, nullable = false)
    private String status;

    @Version
    @Column(name = "version", nullable = false)
    private Long version;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    private GroupSeason(Group group, Season season, Instant now) {
        this.group = group;
        this.season = season;
        this.selectionDeadlineAt = now.plusSeconds(72L * 60 * 60);
        this.status = "PREPARING";
        this.createdAt = now;
        this.updatedAt = now;
    }

    /** 시즌 중 생성된 그룹의 현재 시즌 참여를 시작합니다. */
    public static GroupSeason start(Group group, Season season, Instant now) {
        return new GroupSeason(group, season, now);
    }

    /**
     * 부화 기한 전 관리자의 최종 크리처 종 선택을 반영합니다.
     *
     * @param species 선택한 활성 종
     * @param selectedBy 선택한 주·부 관리자
     * @param now 선택 시각
     * @return 기존 선택과 달라 상태를 변경했으면 {@code true}
     */
    public boolean selectSpecies(CreatureSpecies species, User selectedBy, Instant now) {
        if (!isSelectionOpenAt(now)) {
            throw new IllegalStateException("Creature selection is closed.");
        }
        if (this.selectedSpecies != null && Objects.equals(this.selectedSpecies.getId(), species.getId())) {
            return false;
        }
        this.selectedSpecies = species;
        this.selectedByUser = selectedBy;
        this.selectionSource = "ADMIN";
        this.selectedAt = now;
        this.updatedAt = now;
        return true;
    }

    /** 선택되지 않은 종을 부화 시점에 무작위 종으로 확정합니다. */
    public void selectRandomSpecies(CreatureSpecies species, Instant now) {
        if (selectedSpecies != null) {
            return;
        }
        this.selectedSpecies = species;
        this.selectedByUser = null;
        this.selectionSource = "RANDOM";
        this.selectedAt = now;
        this.updatedAt = now;
    }

    /** 알 준비 상태를 종료하고 현재 시즌의 활성 크리처 상태로 전환합니다. */
    public void activate(Instant now) {
        if (!"PREPARING".equals(status) || selectedSpecies == null) {
            throw new IllegalStateException("Only a selected preparing group season can be activated.");
        }
        this.status = "ACTIVE";
        this.updatedAt = now;
    }

    /** 현재 시각에 관리자 종 선택이 가능한지 반환합니다. */
    public boolean isSelectionOpenAt(Instant now) {
        return "PREPARING".equals(status) && now.isBefore(selectionDeadlineAt);
    }

    /**
     * 그룹 비활성화에 따라 현재 시즌 참여를 종료합니다.
     *  - 준비 중인 알과 활성 시즌 참여 모두 종료
     *  - 이미 확정된 종 선택과 시즌 이력은 보존
     */
    public void endForGroupClosure(Instant now) {
        if ("ENDED".equals(this.status)) {
            return;
        }

        // 시즌 참가 상태만 종료
        this.status = "ENDED";
        this.updatedAt = now;
    }
}
