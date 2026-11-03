package com.team.sharedcreature.group.domain;

import com.team.sharedcreature.auth.domain.User;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * game_groups 테이블의 상태와 이력을 보존하는 JPA 엔티티입니다.
 */
@Getter
@Entity
@Table(name = "game_groups")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Group {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "name", length = 30, nullable = false)
    private String name;

    @Column(name = "normalized_name", length = 30, unique = true, nullable = false)
    private String normalizedName;

    @Column(name = "description", length = 500, nullable = false)
    private String description;

    @Column(name = "visibility", length = 30, nullable = false)
    private String visibility;

    @Column(name = "status", length = 30, nullable = false)
    private String status;

    @Column(name = "capacity", nullable = false)
    private Integer capacity;

    @Column(name = "current_member_count", nullable = false)
    private Integer currentMemberCount;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "primary_admin_user_id", nullable = true)
    private User primaryAdminUser;

    @Version
    @Column(name = "version", nullable = false)
    private Long version;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    private Group(String name, String normalizedName, String description, User creator, Instant now) {
        this.name = name;
        this.normalizedName = normalizedName;
        this.description = description;
        this.visibility = "PUBLIC";
        this.status = "ACTIVE";
        this.capacity = 20;
        this.currentMemberCount = 1;
        this.primaryAdminUser = creator;
        this.createdAt = now;
        this.updatedAt = now;
    }

    /** 신규 공개 그룹과 최초 주 관리자를 생성합니다. */
    public static Group create(String name, String normalizedName, String description, User creator, Instant now) {
        return new Group(name, normalizedName, description, creator, now);
    }
}
