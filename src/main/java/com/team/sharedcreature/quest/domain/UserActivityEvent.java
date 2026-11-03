package com.team.sharedcreature.quest.domain;

import com.team.sharedcreature.auth.domain.User;
import com.team.sharedcreature.group.domain.GroupMembership;
import com.team.sharedcreature.season.domain.GroupSeason;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.time.LocalDate;

/**
 * user_activity_events 테이블의 상태와 이력을 보존하는 JPA 엔티티입니다.
 */
@Getter
@Entity
@Table(name = "user_activity_events")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class UserActivityEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Column(name = "event_type", length = 50, nullable = false)
    private String eventType;

    @Column(name = "source_type", length = 50, nullable = false)
    private String sourceType;

    @Column(name = "source_id", nullable = false)
    private Long sourceId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "membership_id", nullable = false)
    private GroupMembership membership;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "group_season_id", nullable = false)
    private GroupSeason groupSeason;

    @Column(name = "business_date", nullable = false)
    private LocalDate businessDate;

    @Column(name = "occurred_at", nullable = false)
    private Instant occurredAt;

    @Column(name = "deduplication_key", length = 128, unique = true, nullable = false)
    private String deduplicationKey;

    @Column(name = "metadata", nullable = false)
    private String metadata;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;
}
