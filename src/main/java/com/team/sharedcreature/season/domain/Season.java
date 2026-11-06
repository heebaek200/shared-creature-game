package com.team.sharedcreature.season.domain;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.Objects;

/**
 * seasons 테이블의 상태와 이력을 보존하는 JPA 엔티티입니다.
 */
@Getter
@Entity
@Table(name = "seasons", indexes =
        @Index(name = "ix_seasons_status_start", columnList = "status, start_at"))
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Season {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "season_number", unique = true, nullable = false)
    private Integer seasonNumber;

    @Column(name = "start_at", unique = true, nullable = false)
    private Instant startAt;

    @Column(name = "end_at", nullable = false)
    private Instant endAt;

    @Column(name = "status", length = 30, nullable = false)
    private String status;

    @Version
    @Column(name = "version", nullable = false)
    private Long version;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    /**
     * 특정 시각에 현재 시즌으로 사용할 수 있는지 확인하는 기능.
     * - ACTIVE여야함
     * - 시작 시각 이상이어야 함
     * - 종료 시각 미만이어야 함
     * (Scheduler가 종료 상태로 변경하기 전이라도 시간 경계를 적용)
     */
    public boolean isActiveAt(Instant now) {
        Objects.requireNonNull(now, "now");

        return "ACTIVE".equals(status) && ! now.isBefore(startAt) && now.isBefore(endAt);


    }
}
