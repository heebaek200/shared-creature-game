package com.team.sharedcreature.group.domain;

import com.team.sharedcreature.auth.domain.User;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * group_memberships 테이블의 상태와 이력을 보존하는 JPA 엔티티입니다.
 */
@Getter
@Entity
@Table(name = "group_memberships", indexes = {
        @Index(name = "ix_group_memberships_group_status_role", columnList = "group_id, status, role"),
        @Index(name = "ix_group_memberships_user_joined", columnList = "user_id, joined_at")
})
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class GroupMembership {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "group_id", nullable = false)
    private Group group;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Column(name = "role", length = 30, nullable = false)
    private String role;

    @Column(name = "status", length = 30, nullable = false)
    private String status;

    @Column(name = "joined_at", nullable = false)
    private Instant joinedAt;

    @Column(name = "ended_at")
    private Instant endedAt;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "ended_by_user_id")
    private User endedByUser;

    @Column(name = "exit_reason", length = 30, nullable = true)
    private String exitReason;

    @Column(
            name = "active_user_id",
            insertable = false,
            updatable = false,
            nullable = true
    )
    private Long activeUserId;

    @Column(
            name = "primary_group_id",
            insertable = false,
            updatable = false,
            nullable = true
    )
    private Long primaryGroupId;

    @Version
    @Column(name = "version", nullable = false)
    private Long version;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    private GroupMembership(Group group, User user, String role, Instant now) {
        this.group = group;
        this.user = user;
        this.role = role;
        this.status = "ACTIVE";
        this.joinedAt = now;
        this.createdAt = now;
        this.updatedAt = now;
    }

    /** 그룹 생성자를 활성 주 관리자 멤버십으로 등록합니다. */
    public static GroupMembership primary(Group group, User user, Instant now) {
        return new GroupMembership(group, user, "PRIMARY", now);
    }
}
