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

    /**
     * 종료 절차에 진입하기 직전의 시즌 상태.
     * ACTIVE에서 종료되었는지 SCHEDULED에서 만료되었는지 구분함.
     * CLOSING 중 서버가 재시작하더라도 Snapshot 처리 기준을 보존한다.
     */
    @Column(name = "closing_from_status", length = 30)
    private String closingFromStatus;

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

    /**
     * 그룹 초기화가 완료된 SCHEDULED 시즌을 활성화한다.
     * 시작 시각 이상이면서 종료 시각 미만일 때만 전환한다.
     * 실제 그룹 초기화 완료 여부는 호출 Service가 확인해야 한다.
     */
    public boolean activateAfterInitialization(Instant now) {
        Objects.requireNonNull(now, "now");

        // 이미 활성화된 시즌은 다시 갱신하지 않는다
        if ("ACTIVE".equals(status)) {
            return false;
        }

        if (! "SCHEDULED".equals(status) || now.isBefore(startAt) || !now.isBefore(endAt)) {
            throw new IllegalStateException("현재 시즌을 활성화할 수 없습니다.");
        }

        // 그룹 초기화 완료는 호출자가 검증한 뒤 실행합니다.
        status = "ACTIVE";
        updatedAt = now;

        return true;
    }

    /**
     * 만료된 시즌을 종료 준비 상태로 변경한다.
     * ACTIVE와 SCHEDULED 상태에서 모두 종료를 시작할 수 있다.
     * 아직 종료 시각에 도달하지 않았으면 상태를 유지한다.
     */
    public boolean beginClosingIfExpired(Instant now) {
        Objects.requireNonNull(now, "now");

        // 종료 중이거나 이미 종료됐다면 다시 변경하지 않는다.
        if ("CLOSING".equals(status) || "ENDED".equals(status)) {
            return false;
        }

        if (!"ACTIVE".equals(status) && ! "SCHEDULED".equals(status)) {
            throw new IllegalStateException("현재 시즌에서 종료를 시작할 수 없습니다.");
        }

        // 아직 시즌 종료 시각 전이라면 작업하지 않는다.
        if (now.isBefore(endAt)) {
            return false;
        }

        closingFromStatus = status;
        status = "CLOSING";
        updatedAt = now;

        return true;
    }

    /**
     * 종료 Snapshot 처리가 끝난 시즌을 최종 종료한다.
     * 반드시 CLOSING 상태에서만 ENDED로 전환할 수 있다.
     * Snapshot 완료 여부는 호출 Service가 검증해야 한다.
     */
    public boolean finishClosingAfterSnapshot(Instant now) {
        Objects.requireNonNull(now, "now");

        // 중복 정상화에서는 종료 시각을 다시 기록하지 않습니다.
        if ("ENDED".equals(status)) {
            return false;
        }

        if (! "CLOSING".equals(status)) {
            throw new IllegalStateException("CLOSING 상태의 시즌만 종료할 수 있습니다.");
        }

        status = "ENDED";
        updatedAt = now;

        return true;
    }

    /**
     * 종료 중인 시즌에 랭킹 Snapshot이 필요한지 판정한다.
     * ACTIVE에서 종료된 시즌만 Snapshot을 생성한다.
     * SCHEDULED에서 만료된 시즌은 Snapshot을 생성하지 않는다.
     */
    public boolean requiresRankingSnapshotOnClosing() {
        if (! "CLOSING".equals(status)) {
            throw new IllegalStateException("CLOSING 상태에서만 Snapshot 필요 여부를 판정할 수 있습니다.");
        }

        // ACTIVE에서 종료된 시즌은 랭킹 확정
        if ("ACTIVE".equals(closingFromStatus)) {
            return true;
        }

        // 초기화 중 만료된 시즌은 랭킹을 소급 생성하지 않는다.
        if ("SCHEDULED".equals(closingFromStatus)) {
            return false;
        }

        throw new IllegalStateException("시즌 종료 이전 상태를 확인할 수 없습니다.");
    }

    /**
     * 현재 달력 구간의 SCHEDULED 시즌을 신규 생성합니다.
     * 실제 최초 생성 시각을 기록하고 달력상 4주 구간은 별도로 유지합니다.
     * 만료된 과거 구간이나 잘못된 28일 간격은 생성하지 않습니다.
     *
     * @param seasonNumber 마지막 DB 시즌 번호 + 1
     * @param startAt 최초 시즌 달력 기준 현재 구간 시작
     * @param endAt 28일 뒤 구간 종료
     * @param now 실제 생성 시각
     * @return 아직 그룹 초기화를 수행하지 않은 SCHEDULED 시즌
     */
    public static Season schedule(
            int seasonNumber,
            Instant startAt,
            Instant endAt,
            Instant now
    ) {
        Objects.requireNonNull(startAt, "startAt");
        Objects.requireNonNull(endAt, "endAt");
        Objects.requireNonNull(now, "now");

        // 종료된 달력을 소급 생성하지 않으며 정확한 28일 기간만 인정합니다.
        if (seasonNumber < 1
                || !endAt.equals(startAt.plus(java.time.Duration.ofDays(28)))
                || now.isBefore(startAt)
                || !now.isBefore(endAt)) {
            throw new IllegalArgumentException("유효하지 않은 신규 시즌 구간입니다.");
        }

        Season season = new Season();
        season.seasonNumber = seasonNumber;
        season.startAt = startAt;
        season.endAt = endAt;
        season.status = "SCHEDULED";
        season.closingFromStatus = null;
        season.createdAt = now;
        season.updatedAt = now;
        return season;
    }

}
