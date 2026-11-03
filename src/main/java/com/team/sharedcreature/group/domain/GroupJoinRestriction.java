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
@Table(name = "group_join_restrictions")
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
}
