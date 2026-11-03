package com.team.sharedcreature.group.domain;

import com.team.sharedcreature.auth.domain.User;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * group_admin_changes 테이블의 상태와 이력을 보존하는 JPA 엔티티입니다.
 */
@Getter
@Entity
@Table(name = "group_admin_changes")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class GroupAdminChange {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "group_id", nullable = false)
    private Group group;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "target_user_id", nullable = false)
    private User targetUser;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "actor_user_id", nullable = false)
    private User actorUser;

    @Column(name = "change_type", length = 30, nullable = false)
    private String changeType;

    @Column(name = "previous_role", length = 30, nullable = true)
    private String previousRole;

    @Column(name = "new_role", length = 30, nullable = true)
    private String newRole;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    private GroupAdminChange(Group group, User user, Instant now) {
        this.group = group;
        this.targetUser = user;
        this.actorUser = user;
        this.changeType = "PRIMARY_CREATED";
        this.newRole = "PRIMARY";
        this.createdAt = now;
    }

    /** 그룹 생성 시 최초 주 관리자 지정 이력을 생성합니다. */
    public static GroupAdminChange initialPrimary(Group group, User user, Instant now) {
        return new GroupAdminChange(group, user, now);
    }
}
