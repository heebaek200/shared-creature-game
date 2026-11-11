package com.team.sharedcreature.auth.domain;

import com.team.sharedcreature.group.domain.Group;
import com.team.sharedcreature.group.domain.GroupMembership;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.time.LocalDate;

/** 인증 API의 KST 일별 활동과 발생 당시 그룹 범위를 보존합니다. */
@Getter
@Entity
@Table(name = "user_daily_activities", uniqueConstraints =
        @UniqueConstraint(
                name = "uq_user_daily_activities_scope",
                columnNames = {"user_id", "business_date", "activity_membership_key"}
        ), indexes =
        @Index(name = "ix_user_daily_activities_date_group", columnList = "business_date, group_id_snapshot, user_id"))
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class UserDailyActivity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "membership_id")
    private GroupMembership membership;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "group_id_snapshot")
    private Group groupSnapshot;

    @Column(name = "business_date", nullable = false)
    private LocalDate businessDate;

    @Column(name = "first_active_at", nullable = false)
    private Instant firstActiveAt;

    @Column(name = "last_active_at", nullable = false)
    private Instant lastActiveAt;

    @Column(name = "activity_membership_key", insertable = false, updatable = false)
    private Long activityMembershipKey;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;
}
