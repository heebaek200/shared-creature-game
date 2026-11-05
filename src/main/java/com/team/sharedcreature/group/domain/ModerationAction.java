package com.team.sharedcreature.group.domain;

import com.team.sharedcreature.auth.domain.User;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * moderation_actions 테이블의 상태와 이력을 보존하는 JPA 엔티티입니다.
 */
@Getter
@Entity
@Table(name = "moderation_actions", indexes = {
        @Index(name = "ix_moderation_actions_target_executed", columnList = "target_user_id, executed_at"),
        @Index(name = "ix_moderation_actions_group_executed", columnList = "group_id, executed_at")
})
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ModerationAction {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "report_id", nullable = true)
    private UserReport report;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "group_id", nullable = false)
    private Group group;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "membership_id", nullable = false)
    private GroupMembership membership;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "target_user_id", nullable = false)
    private User targetUser;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "actor_user_id", nullable = false)
    private User actorUser;

    @Column(name = "action_type", length = 30, nullable = false)
    private String actionType;

    @Column(name = "reason", length = 1000, nullable = false)
    private String reason;

    @Column(name = "executed_at", nullable = false)
    private Instant executedAt;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    /**
     * 비활동 회원 강퇴에 대한 감사 기록 생성 (팩토리 패턴)
     */
    public static ModerationAction inactiveRemoval(
            Group group,
            GroupMembership membership,
            User target,
            User actor,
            String reason,
            Instant now
    ) {
        ModerationAction action = new ModerationAction();

        // 신고 심사에 의한 강퇴와의 구분으로 report = null
        action.report = null;
        action.group = group;
        action.membership = membership;
        action.targetUser = target;
        action.actorUser = actor;
        action.actionType = "REMOVE_INACTIVE";
        action.reason = reason;
        action.executedAt = now;
        action.createdAt = now;

        return action;
    }
}
