package com.team.sharedcreature.season.domain;

import com.team.sharedcreature.auth.domain.User;
import com.team.sharedcreature.group.domain.Group;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;

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
        this.status = "ACTIVE";
        this.createdAt = now;
        this.updatedAt = now;
    }

    /** 시즌 중 생성된 그룹의 현재 시즌 참여를 시작합니다. */
    public static GroupSeason start(Group group, Season season, Instant now) {
        return new GroupSeason(group, season, now);
    }
}
