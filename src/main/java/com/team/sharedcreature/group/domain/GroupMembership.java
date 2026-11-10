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

    /** 공개 또는 초대 가입자를 활성 일반 구성원으로 등록합니다. */
    public static GroupMembership member(Group group, User user, Instant now) {
        return new GroupMembership(group, user, "MEMBER", now);
    }

    /**
     * 일반 사용자(MEMBER)와 부 관리자(DEPUTY) 사이에서 그룹 관리자 권한을 변경합니다.
     * 주 관리자(PRIMARY)의 역할은 이 메서드로 부여 또는 회수할 수 _없습니다_.
     * 변경 시각은 기존 멤버십의 수정 시각에 함께 기록합니다.
     */
    public void changeDeputyRole(String nextRole, Instant now) {
        if (!"MEMBER".equals(nextRole) && !"DEPUTY".equals(nextRole)) {
            // 허용되지 않은 역할은 도메인에서도 거부
            throw new IllegalArgumentException("변경할 수 없는 역할입니다.");
        }

        if ("PRIMARY".equals(this.role)) {
            throw new IllegalStateException("주 관리자 역할은 직접 변경할 수 없습니다.");
        }

        this.role = nextRole;
        this.updatedAt = now;
    }

    /**
     * 주 관리자(PRIMARY) 이전 과정에서 기존 관리자를 일반 사용자(MEMBER)로 변경합니다.
     *  - 신규 관리자를 지정하기 전에 호출됨
     *  - 그룹별 PRIMARY UNIQUE 제약을 위한 로직
     */
    public void demotePrimary(Instant now) {
        if (!"PRIMARY".equals(this.role)) {
            throw new IllegalStateException("현재 주 관리자가 아닙니다.");
        }

        // 일반 사용자로 강등
        this.role = "MEMBER";
        this.updatedAt = now;
    }

    /**
     * 기존 활성 회원을 새로운 주 관리자(PRIMARY)로 승격합니다.
     *  - 이전 역할은 일반 사용자(MEMBER) 또는 부 관리자(DEPUTY)여야 함
     *  - 기존 주 관리자 해제 이후에 호출해야 함
     */
    public void promotePrimary(Instant now) {
        if (!"MEMBER".equals(this.role) && !"DEPUTY".equals(this.role)) {
            throw new IllegalStateException("주 관리자로 이전할 수 없는 역할입니다");
        }

        // 주 관리자로 승격
        this.role = "PRIMARY";
        this.updatedAt = now;
    }

    /**
     * 현재 회원의 자진 탈퇴를 기록합니다.
     *  - 활성 멤버십만 종료할 수 있으며 과거 가입과 역할 기록은 유지함
     *  - 탈퇴자와 종료 실행자를 동일한 사용자로 기록함
     */
    public void leaveBySelf(User actor, Instant now) {
        if (!"ACTIVE".equals(this.status)) {
            throw new IllegalStateException("활성 멤버십이 아닙니다.");
        }

        if (!this.user.getId().equals(actor.getId())) {
            throw new IllegalArgumentException("본인의 멤버십만 탈퇴할 수 있습니다.");
        }

        // 멤버십을 삭제하지 않고 종료 상태로 보존한다
        this.status = "LEFT";
        this.endedAt = now;
        this.endedByUser = actor;
        this.exitReason = "SELF";
        this.updatedAt = now;
    }

    /**
     * 비활동 강퇴가 확정된 멤버십을 종료함
     *  - 주 관리자(PRIMARY)는 강퇴할 수 없음 / 활성 멤버만 종료할 수 있음
     *  - 과거 역할과 가입 기록은 보존 / 실행 관리자와 종료 사유를 감사 레코드로 남김
     */
    public void removeForInactivity(User actor, Instant now) {
        if (!"ACTIVE".equals(this.status)) {
            throw new IllegalStateException("활성 멤버십이 아닙니다.");
        }

        if ("PRIMARY".equals(this.role)) {
            throw new IllegalStateException("주 관리자는 강퇴할 수 없습니다.");
        }

        if (this.user.getId().equals(actor.getId())) {
            throw new IllegalArgumentException("자기 자신을 강퇴할 수 없습니다.");
        }

        this.status = "REMOVED";
        this.endedAt = now;
        this.endedByUser = actor;
        this.exitReason = "INACTIVE";
        this.updatedAt = now;
    }

}
