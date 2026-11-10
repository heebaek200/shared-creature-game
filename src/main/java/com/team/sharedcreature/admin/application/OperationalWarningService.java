package com.team.sharedcreature.admin.application;

import com.team.sharedcreature.admin.api.OperationalWarningResponse;
import jakarta.persistence.EntityManager;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** 현재 업무 원본에서 미해결 운영 경고를 합성합니다. */
@Service
@Transactional(readOnly = true)
public class OperationalWarningService {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    private static final Duration SEASON_LENGTH = Duration.ofDays(28);

    private final EntityManager entityManager;
    private final Clock clock;

    /** 운영 원본 조회에 사용할 영속성 Context와 시계를 주입받습니다. */
    public OperationalWarningService(EntityManager entityManager, Clock clock) {
        this.entityManager = entityManager;
        this.clock = clock;
    }

    /** 유형 필터와 페이지 조건에 맞는 현재 경고를 반환합니다. */
    public Page<OperationalWarningResponse> list(String type, int page, int size) {
        List<OperationalWarningResponse> warnings = currentWarnings().stream()
                .filter(warning -> type == null || type.equals(warning.type()))
                .sorted(Comparator.comparing(OperationalWarningResponse::detectedAt).reversed()
                        .thenComparing(OperationalWarningResponse::warningKey))
                .toList();
        int from = Math.min(page * size, warnings.size());
        int to = Math.min(from + size, warnings.size());
        return new PageImpl<>(warnings.subList(from, to), PageRequest.of(page, size), warnings.size());
    }

    /** 대시보드에서 사용할 전체 현재 경고를 최신순으로 반환합니다. */
    public List<OperationalWarningResponse> currentWarnings() {
        Instant now = clock.instant();
        List<OperationalWarningResponse> warnings = new ArrayList<>();
        addSchedulerWarnings(warnings, now);
        addSeasonWarnings(warnings, now);
        addQuestPeriodWarnings(warnings);
        addPaymentWarnings(warnings);
        addRefundWarnings(warnings);
        addMemberCountWarnings(warnings);
        addPhotoWarnings(warnings, now);
        return warnings;
    }

    private void addSchedulerWarnings(List<OperationalWarningResponse> warnings, Instant now) {
        List<?> rows = entityManager.createNativeQuery("""
                SELECT execution.id
                     , execution.status
                     , execution.completed_at
                     , execution.lease_expires_at
                     , execution.updated_at
                  FROM scheduler_executions execution
                 WHERE execution.status = 'FAILED'
                    OR (execution.status = 'RUNNING' AND execution.lease_expires_at < :now)
                """).setParameter("now", now).getResultList();
        for (Object rowValue : rows) {
            Object[] row = (Object[]) rowValue;
            Long id = number(row[0]);
            boolean leaseExpired = "RUNNING".equals(String.valueOf(row[1]));
            Instant detectedAt = leaseExpired ? instant(row[3]) : firstInstant(row[2], row[4], now);
            warnings.add(warning(
                    "SCHEDULER_FAILED", leaseExpired ? "CRITICAL" : "WARNING",
                    "SCHEDULER_EXECUTION", id,
                    leaseExpired ? "Scheduler Lease가 만료됐습니다." : "Scheduler 실행이 실패했습니다.",
                    detectedAt
            ));
        }
    }

    private void addSeasonWarnings(List<OperationalWarningResponse> warnings, Instant now) {
        List<?> rows = entityManager.createNativeQuery("""
                SELECT season.id
                     , season.status
                     , season.end_at
                  FROM seasons season
                 WHERE (season.status = 'ACTIVE' AND season.end_at <= :now)
                    OR season.status = 'CLOSING'
                """).setParameter("now", now).getResultList();
        for (Object rowValue : rows) {
            Object[] row = (Object[]) rowValue;
            warnings.add(warning(
                    "SEASON_STATE_INVALID", "CRITICAL", "SEASON", number(row[0]),
                    "현재 시각과 시즌 상태가 일치하지 않습니다.", instant(row[2])
            ));
        }

        Number covering = (Number) entityManager.createNativeQuery("""
                SELECT COUNT(season.id)
                  FROM seasons season
                 WHERE season.start_at <= :now
                   AND season.end_at > :now
                """).setParameter("now", now).getSingleResult();
        if (covering.longValue() == 0L) {
            Instant expectedStart = expectedSeasonStart(now);
            String key = "SEASON_STATE_INVALID:SEASON:CURRENT_PERIOD:"
                    + expectedStart.atZone(KST).toLocalDate();
            warnings.add(new OperationalWarningResponse(
                    key, "SEASON_STATE_INVALID", "CRITICAL", "SEASON", null,
                    "현재 4주 구간의 시즌이 없습니다.", expectedStart
            ));
        }
    }

    private void addQuestPeriodWarnings(List<OperationalWarningResponse> warnings) {
        List<?> rows = entityManager.createNativeQuery("""
                SELECT period.id
                     , period.failure_code
                     , period.updated_at
                  FROM quest_periods period
                 WHERE period.status = 'FAILED'
                """).getResultList();
        for (Object rowValue : rows) {
            Object[] row = (Object[]) rowValue;
            String type = "TEMPLATE_SHORTAGE".equals(String.valueOf(row[1]))
                    ? "TEMPLATE_SHORTAGE" : "QUEST_PERIOD_FAILED";
            warnings.add(warning(
                    type, "WARNING", "QUEST_PERIOD", number(row[0]),
                    "퀘스트 기간 배정이 실패했습니다.", instant(row[2])
            ));
        }
    }

    private void addPaymentWarnings(List<OperationalWarningResponse> warnings) {
        List<?> rows = entityManager.createNativeQuery("""
                SELECT transaction.purchase_id
                     , MAX(transaction.updated_at)
                  FROM payment_transactions transaction
                 WHERE transaction.status = 'UNKNOWN'
                 GROUP BY transaction.purchase_id
                """).getResultList();
        for (Object rowValue : rows) {
            Object[] row = (Object[]) rowValue;
            warnings.add(warning(
                    "PAYMENT_UNCERTAIN", "CRITICAL", "PURCHASE", number(row[0]),
                    "결제 결과가 확정되지 않았습니다.", instant(row[1])
            ));
        }
    }

    private void addRefundWarnings(List<OperationalWarningResponse> warnings) {
        List<?> rows = entityManager.createNativeQuery("""
                SELECT refund.id
                     , refund.updated_at
                  FROM refund_requests refund
                 WHERE refund.status = 'CANCEL_UNKNOWN'
                """).getResultList();
        for (Object rowValue : rows) {
            Object[] row = (Object[]) rowValue;
            warnings.add(warning(
                    "REFUND_UNCERTAIN", "CRITICAL", "REFUND_REQUEST", number(row[0]),
                    "환불 취소 결과가 확정되지 않았습니다.", instant(row[1])
            ));
        }
    }

    private void addMemberCountWarnings(List<OperationalWarningResponse> warnings) {
        List<?> rows = entityManager.createNativeQuery("""
                SELECT game_group.id
                     , game_group.updated_at
                     , COUNT(membership.id)
                  FROM game_groups game_group
                  LEFT JOIN group_memberships membership
                    ON membership.group_id = game_group.id
                   AND membership.status = 'ACTIVE'
                 WHERE game_group.status = 'ACTIVE'
                 GROUP BY game_group.id, game_group.current_member_count, game_group.updated_at
                HAVING game_group.current_member_count <> COUNT(membership.id)
                """).getResultList();
        for (Object rowValue : rows) {
            Object[] row = (Object[]) rowValue;
            warnings.add(warning(
                    "MEMBER_COUNT_MISMATCH", "WARNING", "GROUP", number(row[0]),
                    "그룹 인원 카운터와 활성 멤버십 수가 일치하지 않습니다.", instant(row[1])
            ));
        }
    }

    private void addPhotoWarnings(List<OperationalWarningResponse> warnings, Instant now) {
        List<?> rows = entityManager.createNativeQuery("""
                SELECT submission.id
                     , CASE
                           WHEN submission.verification_status = 'VALID'
                           THEN DATE_ADD(season.end_at, INTERVAL 30 DAY)
                           ELSE DATE_ADD(submission.updated_at, INTERVAL 24 HOUR)
                       END AS deletion_due_at
                  FROM photo_submissions submission
                  JOIN quest_assignments assignment ON assignment.id = submission.quest_assignment_id
                  JOIN quest_periods period ON period.id = assignment.quest_period_id
                  JOIN seasons season ON season.id = period.season_id
                 WHERE submission.storage_key IS NOT NULL
                   AND (
                       (submission.verification_status = 'VALID' AND DATE_ADD(season.end_at, INTERVAL 30 DAY) <= :now)
                    OR (submission.verification_status = 'INVALID' AND DATE_ADD(submission.updated_at, INTERVAL 24 HOUR) <= :now)
                   )
                """).setParameter("now", now).getResultList();
        for (Object rowValue : rows) {
            Object[] row = (Object[]) rowValue;
            warnings.add(warning(
                    "PHOTO_DELETION_FAILED", "WARNING", "PHOTO_SUBMISSION", number(row[0]),
                    "보존 기한이 지난 사진 파일이 삭제되지 않았습니다.", instant(row[1])
            ));
        }
    }

    private Instant expectedSeasonStart(Instant now) {
        Object first = entityManager.createNativeQuery("SELECT MIN(season.start_at) FROM seasons season")
                .getSingleResult();
        if (first != null) {
            Instant baseline = instant(first);
            if (!now.isBefore(baseline)) {
                long cycles = Duration.between(baseline, now).dividedBy(SEASON_LENGTH);
                return baseline.plus(SEASON_LENGTH.multipliedBy(cycles));
            }
            return baseline;
        }
        LocalDate monday = now.atZone(KST).toLocalDate()
                .with(TemporalAdjusters.previousOrSame(java.time.DayOfWeek.MONDAY));
        return monday.atStartOfDay(KST).toInstant();
    }

    private OperationalWarningResponse warning(
            String type,
            String severity,
            String resourceType,
            Long resourceId,
            String message,
            Instant detectedAt
    ) {
        return new OperationalWarningResponse(
                type + ":" + resourceType + ":" + resourceId,
                type,
                severity,
                resourceType,
                resourceId,
                message,
                detectedAt
        );
    }

    private Long number(Object value) {
        return ((Number) value).longValue();
    }

    private Instant instant(Object value) {
        if (value instanceof Instant instant) {
            return instant;
        }
        if (value instanceof Timestamp timestamp) {
            return timestamp.toInstant();
        }
        return Instant.parse(String.valueOf(value));
    }

    private Instant firstInstant(Object first, Object second, Instant fallback) {
        if (first != null) {
            return instant(first);
        }
        if (second != null) {
            return instant(second);
        }
        return fallback;
    }
}
