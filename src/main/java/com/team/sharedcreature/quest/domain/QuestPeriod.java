package com.team.sharedcreature.quest.domain;

import com.team.sharedcreature.season.domain.Season;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.time.LocalDate;

/**
 * quest_periods 테이블의 상태와 이력을 보존하는 JPA 엔티티입니다.
 */
@Getter
@Entity
@Table(name = "quest_periods")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class QuestPeriod {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "season_id", nullable = false)
    private Season season;

    @Column(name = "period_type", length = 30, nullable = false)
    private String periodType;

    @Column(name = "period_date", nullable = false)
    private LocalDate periodDate;

    @Column(name = "start_at", nullable = false)
    private Instant startAt;

    @Column(name = "end_at", nullable = false)
    private Instant endAt;

    @Column(name = "status", length = 30, nullable = false)
    private String status;

    @Column(name = "failure_code", length = 50, nullable = false)
    private String failureCode;

    @Column(name = "failure_detail", length = 1000, nullable = false)
    private String failureDetail;

    @Version
    @Column(name = "version", nullable = false)
    private Long version;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;
}
