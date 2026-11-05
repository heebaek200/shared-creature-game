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
@Table(name = "group_admin_changes", indexes = {
        @Index(name = "ix_group_admin_changes_group_created", columnList = "group_id, created_at"),
        @Index(name = "ix_group_admin_changes_target_created", columnList = "target_user_id, created_at")
})
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

    private GroupAdminChange(
            Group group,
            User target,
            User actor,
            String changeType,
            String previousRole,
            String newRole,
            Instant now
    ) {
        this.group = group;
        this.targetUser = target;
        this.actorUser = actor;
        this.changeType = changeType;
        this.previousRole = previousRole;
        this.newRole = newRole;
        this.createdAt = now;
    }

    /** 그룹 생성 시 최초 주 관리자 지정 이력을 생성합니다. */
    public static GroupAdminChange initialPrimary(Group group, User user, Instant now) {
        return new GroupAdminChange(group, user, user,
                "INITIAL_PRIMARY", null, "PRIMARY", now);
    }

    /**
     * 부 관리자 권한 부여 또는 회수 이력을 기록합니다. (팩토리)
     * DEPUTY로 변경하면 GRANT_DEPUTY, MEMBER로 변경하면 REVOKE_DEPUTY. 대상의 이전 역할을 보존함.
     */
    public static GroupAdminChange deputyChanged(
            Group group,
            User actor,
            User target,
            String previousRole,
            String nextRole,
            Instant now
    ) {
        String changeType = "DEPUTY".equals(nextRole) ? "GRANT_DEPUTY" : "REVOKE_DEPUTY";

        return new GroupAdminChange(group, target, actor, changeType, previousRole, nextRole, now);
    }

    /**
     * 주 관리자 이전 기능의 감사 이력을 생성합니다.
     *  - 실행자는 구 주 관리자로 기록합니다.
     *  - 기존 대상 회원이 일반 사용자였는지 부 관리자였는지 기록합니다.
     */
    public static GroupAdminChange primaryTransferred(
            Group group,
            User actor,
            User target,
            String previousRole,
            Instant now
    ) {
        return new GroupAdminChange(
                group, target, actor,
                "TRANSFER_PRIMARY", previousRole, "PRIMARY", now
        );
    }

}
