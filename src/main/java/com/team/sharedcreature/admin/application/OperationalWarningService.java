package com.team.sharedcreature.admin.application;

import com.team.sharedcreature.admin.api.OperationalWarningResponse;
import com.team.sharedcreature.admin.domain.SchedulerExecution;
import com.team.sharedcreature.group.domain.Group;
import com.team.sharedcreature.payment.domain.PaymentTransaction;
import com.team.sharedcreature.payment.domain.RefundRequest;
import com.team.sharedcreature.quest.domain.PhotoSubmission;
import com.team.sharedcreature.quest.domain.QuestPeriod;
import com.team.sharedcreature.season.domain.Season;
import jakarta.persistence.EntityManager;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

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
        List<SchedulerExecution> executions = entityManager.createQuery("""
                SELECT execution
                  FROM SchedulerExecution execution
                 WHERE execution.status = 'FAILED'
                    OR (execution.status = 'RUNNING' AND execution.leaseExpiresAt < :now)
                """, SchedulerExecution.class).setParameter("now", now).getResultList();
        for (SchedulerExecution execution : executions) {
            boolean leaseExpired = "RUNNING".equals(execution.getStatus());
            Instant detectedAt = leaseExpired
                    ? execution.getLeaseExpiresAt()
                    : firstInstant(execution.getCompletedAt(), execution.getUpdatedAt(), now);
            warnings.add(warning(
                    "SCHEDULER_FAILED", leaseExpired ? "CRITICAL" : "WARNING",
                    "SCHEDULER_EXECUTION", execution.getId(),
                    leaseExpired ? "Scheduler Lease가 만료됐습니다." : "Scheduler 실행이 실패했습니다.",
                    detectedAt
            ));
        }
    }

    private void addSeasonWarnings(List<OperationalWarningResponse> warnings, Instant now) {
        List<Season> seasons = entityManager.createQuery("""
                SELECT season
                  FROM Season season
                 WHERE (season.status = 'ACTIVE' AND season.endAt <= :now)
                    OR season.status = 'CLOSING'
                """, Season.class).setParameter("now", now).getResultList();
        for (Season season : seasons) {
            warnings.add(warning(
                    "SEASON_STATE_INVALID", "CRITICAL", "SEASON", season.getId(),
                    "현재 시각과 시즌 상태가 일치하지 않습니다.", season.getEndAt()
            ));
        }

        Long covering = entityManager.createQuery("""
                SELECT COUNT(season.id)
                  FROM Season season
                 WHERE season.startAt <= :now
                   AND season.endAt > :now
                """, Long.class).setParameter("now", now).getSingleResult();
        if (covering == 0L) {
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
        List<QuestPeriod> periods = entityManager.createQuery("""
                SELECT period
                  FROM QuestPeriod period
                 WHERE period.status = 'FAILED'
                """, QuestPeriod.class).getResultList();
        for (QuestPeriod period : periods) {
            String type = "TEMPLATE_SHORTAGE".equals(period.getFailureCode())
                    ? "TEMPLATE_SHORTAGE" : "QUEST_PERIOD_FAILED";
            warnings.add(warning(
                    type, "WARNING", "QUEST_PERIOD", period.getId(),
                    "퀘스트 기간 배정이 실패했습니다.", period.getUpdatedAt()
            ));
        }
    }

    private void addPaymentWarnings(List<OperationalWarningResponse> warnings) {
        List<PaymentTransaction> transactions = entityManager.createQuery("""
                SELECT paymentTransaction
                  FROM PaymentTransaction paymentTransaction
                 WHERE paymentTransaction.transactionType = 'APPROVAL'
                   AND paymentTransaction.status = 'UNKNOWN'
                   AND NOT EXISTS (
                       SELECT later.id
                         FROM PaymentTransaction later
                        WHERE later.purchase = paymentTransaction.purchase
                          AND later.transactionType = 'APPROVAL'
                          AND (
                              later.createdAt > paymentTransaction.createdAt
                           OR (later.createdAt = paymentTransaction.createdAt AND later.id > paymentTransaction.id)
                          )
                   )
                """, PaymentTransaction.class).getResultList();
        for (PaymentTransaction transaction : transactions) {
            warnings.add(warning(
                    "PAYMENT_UNCERTAIN", "CRITICAL", "PURCHASE", transaction.getPurchase().getId(),
                    "결제 결과가 확정되지 않았습니다.", transaction.getUpdatedAt()
            ));
        }
    }

    private void addRefundWarnings(List<OperationalWarningResponse> warnings) {
        List<RefundRequest> refunds = entityManager.createQuery("""
                SELECT refund
                  FROM RefundRequest refund
                 WHERE refund.status = 'CANCEL_UNKNOWN'
                """, RefundRequest.class).getResultList();
        for (RefundRequest refund : refunds) {
            warnings.add(warning(
                    "REFUND_UNCERTAIN", "CRITICAL", "REFUND_REQUEST", refund.getId(),
                    "환불 취소 결과가 확정되지 않았습니다.", refund.getUpdatedAt()
            ));
        }
    }

    private void addMemberCountWarnings(List<OperationalWarningResponse> warnings) {
        List<Group> groups = entityManager.createQuery("""
                SELECT gameGroup
                  FROM Group gameGroup
                 WHERE gameGroup.status = 'ACTIVE'
                   AND gameGroup.currentMemberCount <> (
                       SELECT COUNT(membership.id)
                         FROM GroupMembership membership
                        WHERE membership.group = gameGroup
                          AND membership.status = 'ACTIVE'
                   )
                """, Group.class).getResultList();
        for (Group group : groups) {
            Instant membershipUpdatedAt = entityManager.createQuery("""
                    SELECT MAX(membership.updatedAt)
                      FROM GroupMembership membership
                     WHERE membership.group = :group
                    """, Instant.class).setParameter("group", group).getSingleResult();
            Instant detectedAt = membershipUpdatedAt == null || group.getUpdatedAt().isAfter(membershipUpdatedAt)
                    ? group.getUpdatedAt() : membershipUpdatedAt;
            warnings.add(warning(
                    "MEMBER_COUNT_MISMATCH", "WARNING", "GROUP", group.getId(),
                    "그룹 인원 카운터와 활성 멤버십 수가 일치하지 않습니다.", detectedAt
            ));
        }
    }

    private void addPhotoWarnings(List<OperationalWarningResponse> warnings, Instant now) {
        Instant validCutoff = now.minus(Duration.ofDays(30));
        Instant invalidCutoff = now.minus(Duration.ofHours(24));
        List<PhotoSubmission> submissions = entityManager.createQuery("""
                SELECT submission
                  FROM PhotoSubmission submission
                  JOIN FETCH submission.questAssignment assignment
                  JOIN FETCH assignment.questPeriod period
                  JOIN FETCH period.season season
                 WHERE submission.storageKey IS NOT NULL
                   AND (
                       (submission.verificationStatus = 'VALID' AND season.endAt <= :validCutoff)
                    OR (submission.verificationStatus = 'INVALID' AND submission.updatedAt <= :invalidCutoff)
                   )
                """, PhotoSubmission.class)
                .setParameter("validCutoff", validCutoff)
                .setParameter("invalidCutoff", invalidCutoff)
                .getResultList();
        for (PhotoSubmission submission : submissions) {
            Instant deletionDueAt = "VALID".equals(submission.getVerificationStatus())
                    ? submission.getQuestAssignment().getQuestPeriod().getSeason().getEndAt().plus(Duration.ofDays(30))
                    : submission.getUpdatedAt().plus(Duration.ofHours(24));
            warnings.add(warning(
                    "PHOTO_DELETION_FAILED", "WARNING", "PHOTO_SUBMISSION", submission.getId(),
                    "보존 기한이 지난 사진 파일이 삭제되지 않았습니다.", deletionDueAt
            ));
        }
    }

    private Instant expectedSeasonStart(Instant now) {
        Instant first = entityManager.createQuery("SELECT MIN(season.startAt) FROM Season season", Instant.class)
                .getSingleResult();
        if (first != null) {
            if (!now.isBefore(first)) {
                long cycles = Duration.between(first, now).dividedBy(SEASON_LENGTH);
                return first.plus(SEASON_LENGTH.multipliedBy(cycles));
            }
            return first;
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

    private Instant firstInstant(Instant first, Instant second, Instant fallback) {
        if (first != null) {
            return first;
        }
        if (second != null) {
            return second;
        }
        return fallback;
    }
}
