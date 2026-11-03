package com.team.sharedcreature.quest.domain;

import com.team.sharedcreature.auth.domain.User;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.time.LocalDate;

/**
 * service_check_ins 테이블의 상태와 이력을 보존하는 JPA 엔티티입니다.
 */
@Getter
@Entity
@Table(name = "service_check_ins")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ServiceCheckIn {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "activity_event_id", nullable = false)
    private UserActivityEvent activityEvent;

    @Column(name = "check_in_date", nullable = false)
    private LocalDate checkInDate;

    @Column(name = "checked_in_at", nullable = false)
    private Instant checkedInAt;

    @Column(name = "is_morning", nullable = false)
    private Boolean isMorning;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;
}
