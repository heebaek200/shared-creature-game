package com.team.sharedcreature.group.domain;

import com.team.sharedcreature.auth.domain.User;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * group_join_restrictions 테이블의 상태와 이력을 보존하는 JPA 엔티티입니다.
 */
@Getter
@Entity
@Table(name = "group_join_restrictions", uniqueConstraints =
        @UniqueConstraint(name = "uq_group_join_restrictions_user", columnNames = "user_id"), indexes =
        @Index(name = "ix_group_join_restrictions_until", columnList = "restricted_until"))
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class GroupJoinRestriction {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "source_membership_id", nullable = false)
    private GroupMembership sourceMembership;

    @Column(name = "reason", length = 30, nullable = false)
    private String reason;

    @Column(name = "restricted_from", nullable = false)
    private Instant restrictedFrom;

    @Column(name = "restricted_until", nullable = false)
    private Instant restrictedUntil;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    /**
     * private 생성자
     * 탈퇴 및 강퇴 후 사용자별 그룹 가입 제한 생성함
     *  - 제한 기록은 사용자 당 한 건만 보관
     *  - 가입 제한의 시작과 종료 시각은 실제 멤버십 종료 시각을 기준으로 함
     */
    private GroupJoinRestriction(
            User user,
            GroupMembership membership,
            String reason,
            Instant now
    ) {
        this.user = user;
        this.createdAt = now;

        // 공통 초기화 로직
        renew(membership, reason, now);
    }

    /**
     * 인스턴스 생성 메서드
     * 그룹 탈퇴로 발생한 최초 가입 제한 기록을 생성함
     *  - 룰 : 제한 기간은 탈퇴 시각으로부터 정확히 24시간
     */
    public static GroupJoinRestriction forDeparture(
            User user,
            GroupMembership membership,
            String reason,
            Instant now
    ) {
        return new GroupJoinRestriction(user, membership, reason, now);
    }

    /**
     * 기존 가입 제한 기록을 새로운 탈퇴, 강퇴 정보로 갱신함
     *  - 새 행을 중복 생성하지 않음(사용자별 UNIQUE)
     *  - 기존 기록의 생성 기각을 유지하면서 제한 근거와 종료 시각을 갱신함
     */
    public void renew(
            GroupMembership membership,
            String reason,
            Instant now
    ) {
        // 최신 멤버십 종료 정보를 기준으로 제한 기간 재설정
        this.sourceMembership = membership;
        this.reason = reason;
        this.restrictedFrom = now;
        this.restrictedUntil = now.plusSeconds(24L * 60 * 60); // 24시간
        this.updatedAt = now;
    }
}
