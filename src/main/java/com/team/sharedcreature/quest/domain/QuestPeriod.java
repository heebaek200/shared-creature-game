package com.team.sharedcreature.quest.domain;

import com.team.sharedcreature.season.domain.Season;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Objects;

/**
 * quest_periods 테이블의 상태와 이력을 보존하는 JPA 엔티티입니다.
 */
@Getter
@Entity
@Table(name = "quest_periods", uniqueConstraints =
@UniqueConstraint(name = "uq_quest_periods_type_start", columnNames = {"period_type", "start_at"}), indexes =
@Index(name = "ix_quest_periods_status_start", columnList = "status, start_at"))
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

    @Column(name = "failure_code", length = 50)
    private String failureCode;

    @Column(name = "failure_detail", length = 1000)
    private String failureDetail;

    @Version
    @Column(name = "version", nullable = false)
    private Long version;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    /**
     * 아직 확정되지 않은 퀘스트 기간을 생성합니다.
     * 생성 트랜잭션에서 모든 슬롯을 저장한 뒤 ACTIVE로 전환합니다.
     * 중간 실패 시 전체 트랜잭션을 롤백해 부분 배정을 방지합니다.
     */
    public static QuestPeriod pending(
            Season season, String type, LocalDate date, Instant start, Instant end, Instant now
    ) {
        Objects.requireNonNull(season, "season");
        Objects.requireNonNull(date, "date");
        Objects.requireNonNull(start, "start");
        Objects.requireNonNull(end, "end");
        Objects.requireNonNull(now, "now");
        if (!"DAILY".equals(type) && !"WEEKLY".equals(type)) {
            throw new IllegalArgumentException("지원하지 않는 기간: " + type);
        }
        if (!start.isBefore(end)) {
            throw new IllegalArgumentException("기간 종료는 시작보다 나중이어야 합니다.");
        }
        // DB UNIQUE(period_type,start_at)를 최종 중복 방지 수단으로 사용합니다.
        QuestPeriod period = new QuestPeriod();
        period.season = season;
        period.periodType = type;
        period.periodDate = date;
        period.startAt = start;
        period.endAt = end;
        period.status = "PENDING";
        period.createdAt = now;
        period.updatedAt = now;
        return period;
    }

    /**
     * 이전에 실패한 기간을 다시 배정할 수 있는 상태로 전환합니다.
     * ACTIVE·CLOSED를 덮어쓰지 않고 실패 사유를 초기화합니다.
     * 재시도 시 기존 기간 ID와 최초 생성 시각을 유지합니다.
     */
    public void prepareRetry(Instant now) {
        Objects.requireNonNull(now, "now");
        if (!"FAILED".equals(status) && !"PENDING".equals(status)) {
            throw new IllegalStateException("재시도할 수 없는 기간: " + status);
        }
        // 아직 확정된 배정이 없는지 호출 서비스가 먼저 검증해야 합니다.
        status = "PENDING";
        failureCode = null;
        failureDetail = null;
        updatedAt = now;
    }

    /**
     * 모든 슬롯의 INSERT가 성공한 경우에만 기간을 활성화합니다.
     * 실패 이력을 지워 조회 API에서 정상 배정으로 인식되게 합니다.
     * 같은 트랜잭션에서 Snapshot과 함께 Commit되어야 합니다.
     */
    public void activate(Instant now) {
        Objects.requireNonNull(now, "now");
        if (!"PENDING".equals(status)) {
            throw new IllegalStateException("PENDING 기간만 활성화할 수 있습니다.");
        }
        // Snapshot이 전부 준비됐다는 검증은 호출 서비스가 책임집니다.
        status = "ACTIVE";
        failureCode = null;
        failureDetail = null;
        updatedAt = now;
    }

    /**
     * 선택 실패 원인을 FAILED 기간으로 기록합니다.
     * 한 번 ACTIVE로 확정한 Snapshot은 실패로 되돌리지 않습니다.
     * 실패 상세는 DB 컬럼 제한 길이인 최대 1000자로 보존합니다.
     */
    public void fail(String code, String detail, Instant now) {
        Objects.requireNonNull(code, "code");
        Objects.requireNonNull(now, "now");
        if (!"PENDING".equals(status) && !"FAILED".equals(status)) {
            throw new IllegalStateException("실패 처리할 수 없는 기간: " + status);
        }
        if (code.isBlank() || code.length() > 50) {
            throw new IllegalArgumentException("failureCode는 1~50자여야 합니다.");
        }
        // Snapshot을 생성하지 않은 실패만 기록하고 다음 실행에서 재시도를 허용합니다.
        status = "FAILED";
        failureCode = code;
        failureDetail = detail == null ? null : detail.substring(0, Math.min(detail.length(), 1000));
        updatedAt = now;
    }
}
