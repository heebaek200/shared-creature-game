package com.team.sharedcreature.admin.domain;

import com.team.sharedcreature.auth.domain.User;
import com.team.sharedcreature.auth.domain.GlobalRole;
import com.team.sharedcreature.group.domain.Group;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

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

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "actor_role_snapshot", length = 30, nullable = false)
    private GlobalRole actorRoleSnapshot;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "action_type", length = 100, nullable = false)
    private AdminAuditActionType actionType;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "target_type", length = 50, nullable = false)
    private AdminAuditTargetType targetType;

    @Column(name = "target_id", nullable = false)
    private Long targetId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "group_id", nullable = true)
    private Group group;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "result", length = 30, nullable = false)
    private AdminAuditResult result;

    @Column(name = "reason", length = 1000, nullable = false)
    private String reason;

    @Column(name = "detail", columnDefinition = "JSON", nullable = false)
    private String detail;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    /** 전체 관리자 명령의 성공 또는 업무 실패 감사 레코드를 생성합니다. */
    public static AdminAuditLog create(
            User actor,
            AdminAuditActionType actionType,
            AdminAuditTargetType targetType,
            Long targetId,
            Group group,
            AdminAuditResult result,
            String reason,
            String detail,
            Instant now
    ) {
        AdminAuditLog log = new AdminAuditLog();
        log.actorUser = actor;
        // 명령 실행 당시의 역할 이름을 감사 이력에 보존합니다.
        log.actorRoleSnapshot = actor.getGlobalRole();
        log.actionType = actionType;
        log.targetType = targetType;
        log.targetId = targetId;
        log.group = group;
        log.result = result;
        log.reason = reason;
        log.detail = detail;
        log.createdAt = now;
        return log;
    }
}
