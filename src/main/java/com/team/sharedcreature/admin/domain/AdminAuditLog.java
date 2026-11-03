package com.team.sharedcreature.admin.domain;

import com.team.sharedcreature.auth.domain.User;
import com.team.sharedcreature.group.domain.Group;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * admin_audit_logs 테이블의 상태와 이력을 보존하는 JPA 엔티티입니다.
 */
@Getter
@Entity
@Table(name = "admin_audit_logs", indexes = {
        @Index(name = "ix_admin_audit_actor_created", columnList = "actor_user_id, created_at"),
        @Index(name = "ix_admin_audit_target_created", columnList = "target_type, target_id, created_at"),
        @Index(name = "ix_admin_audit_group_created", columnList = "group_id, created_at"),
        @Index(name = "ix_admin_audit_action_created", columnList = "action_type, created_at")
})
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class AdminAuditLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "actor_user_id", nullable = false)
    private User actorUser;

    @Column(name = "actor_role_snapshot", length = 30, nullable = false)
    private String actorRoleSnapshot;

    @Column(name = "action_type", length = 100, nullable = false)
    private String actionType;

    @Column(name = "target_type", length = 50, nullable = false)
    private String targetType;

    @Column(name = "target_id", nullable = false)
    private Long targetId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "group_id", nullable = true)
    private Group group;

    @Column(name = "result", length = 30, nullable = false)
    private String result;

    @Column(name = "reason", length = 500, nullable = false)
    private String reason;

    @Column(name = "detail", columnDefinition = "JSON", nullable = false)
    private String detail;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;
}
