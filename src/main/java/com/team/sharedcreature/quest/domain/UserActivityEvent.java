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
@Table(name = "user_activity_events", indexes = {
        @Index(name = "ix_activity_events_user_type_date", columnList = "user_id, event_type, business_date"),
        @Index(name = "ix_activity_events_membership_group_type_time", columnList = "membership_id, group_season_id, event_type, occurred_at")
})
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

    @Column(name = "deduplication_key", length = 128, unique = true)
    private String deduplicationKey;

    @Column(name = "metadata", columnDefinition = "JSON")
    private String metadata;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    /**
     * 서버에서 확인한 사용자 활동 기록을 생성합니다.
     *
     * @param user 활동을 수행한 사용자
     * @param eventType 활동 종류
     * @param sourceType 활동이 발생한 기능 또는 출처 종류
     * @param sourceId 출처 기능에서 사용하는 식별자
     * @param membership 활동 당시의 그룹 멤버십
     * @param groupSeason 활동을 귀속할 그룹 시즌
     * @param businessDate 활동이 속하는 KST 날짜
     * @param occurredAt 활동 발생 시각
     * @param deduplicationKey 중복 활동을 구분하는 키
     * @param metadata 활동에 추가로 기록할 정보
     */
    public static UserActivityEvent create(
            User user,
            String eventType,
            String sourceType,
            Long sourceId,
            GroupMembership membership,
            GroupSeason groupSeason,
            LocalDate businessDate,
            Instant occurredAt,
            String deduplicationKey,
            String metadata
    ) {
        UserActivityEvent event = new UserActivityEvent();
        event.user = user;
        event.eventType = eventType;
        event.sourceType = sourceType;
        event.sourceId = sourceId;
        event.membership = membership;
        event.groupSeason = groupSeason;
        event.businessDate = businessDate;
        event.occurredAt = occurredAt;
        event.deduplicationKey = deduplicationKey;
        event.metadata = metadata;
        event.createdAt = occurredAt;
        return event;
    }
}
