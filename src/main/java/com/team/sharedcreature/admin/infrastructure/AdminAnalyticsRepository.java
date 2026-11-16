package com.team.sharedcreature.admin.infrastructure;

import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import org.springframework.stereotype.Repository;

import java.sql.Date;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 관리자 대시보드의 읽기 전용 집계를 원본 테이블에서 계산합니다. */
@Repository
public class AdminAnalyticsRepository {

    private final EntityManager entityManager;

    /** JPQL 집계와 제한된 KST 일별 SQL 실행에 사용할 영속성 Context를 주입받습니다. */
    public AdminAnalyticsRepository(EntityManager entityManager) {
        this.entityManager = entityManager;
    }

    public long countActiveUsers(LocalDate startDate, LocalDate endDate, Long groupId) {
        String jpql = """
                SELECT COUNT(DISTINCT activity.user.id)
                  FROM UserDailyActivity activity
                 WHERE activity.businessDate >= :startDate
                   AND activity.businessDate <= :endDate
                """ + groupCondition("activity.groupSnapshot.id", groupId);
        return number(query(jpql, groupId)
                .setParameter("startDate", startDate)
                .setParameter("endDate", endDate)
                .getSingleResult());
    }

    public long countActiveGroups(LocalDate startDate, LocalDate endDate, Long groupId) {
        String jpql = """
                SELECT COUNT(DISTINCT activity.groupSnapshot.id)
                  FROM UserDailyActivity activity
                 WHERE activity.businessDate >= :startDate
                   AND activity.businessDate <= :endDate
                   AND activity.groupSnapshot IS NOT NULL
                """ + groupCondition("activity.groupSnapshot.id", groupId);
        return number(query(jpql, groupId)
                .setParameter("startDate", startDate)
                .setParameter("endDate", endDate)
                .getSingleResult());
    }

    public Map<LocalDate, Long> dailyActiveUsers(LocalDate startDate, LocalDate endDate, Long groupId) {
        String jpql = """
                SELECT activity.businessDate
                     , COUNT(DISTINCT activity.user.id)
                  FROM UserDailyActivity activity
                 WHERE activity.businessDate >= :startDate
                   AND activity.businessDate <= :endDate
                """ + groupCondition("activity.groupSnapshot.id", groupId) + """
                 GROUP BY activity.businessDate
                 ORDER BY activity.businessDate
                """;
        Query query = query(jpql, groupId)
                .setParameter("startDate", startDate)
                .setParameter("endDate", endDate);
        Map<LocalDate, Long> result = new LinkedHashMap<>();
        for (Object rowValue : query.getResultList()) {
            Object[] row = (Object[]) rowValue;
            result.put(localDate(row[0]), number(row[1]));
        }
        return result;
    }

    public Map<LocalDate, Long> dailyQuestCompletions(Instant start, Instant end, Long groupId) {
        /*
         * =================================================================================================
         *  ★ 의도적으로 유지하는 Native SQL — 일반 JPQL 리팩토링 대상이 아닙니다 ★
         *
         *  UTC completed_at을 MySQL에서 KST(+09:00) 날짜로 변환한 뒤 GROUP BY 해야 합니다.
         *  원본 행을 Java로 전부 가져오거나 날짜별 쿼리를 반복하지 않고 DB에서 한 번에 집계하기 위한 예외입니다.
         *  DB 독립적인 KST 날짜 Snapshot 컬럼이 도입되기 전에는 기계적으로 JPQL로 변경하지 않습니다.
         * =================================================================================================
         */
        String sql = """
                SELECT DATE(DATE_ADD(completion.completed_at, INTERVAL 9 HOUR)) AS business_date
                     , COUNT(completion.id)
                  FROM quest_completions completion
                  JOIN group_memberships membership ON membership.id = completion.membership_id
                 WHERE completion.completed_at >= :startAt
                   AND completion.completed_at < :endAt
                """ + groupCondition("membership.group_id", groupId) + """
                 GROUP BY business_date
                 ORDER BY business_date
                """;
        Query query = nativeQuery(sql, groupId)
                .setParameter("startAt", start)
                .setParameter("endAt", end);
        Map<LocalDate, Long> result = new LinkedHashMap<>();
        for (Object rowValue : query.getResultList()) {
            Object[] row = (Object[]) rowValue;
            result.put(localDate(row[0]), number(row[1]));
        }
        return result;
    }

    public long countQuestOpportunities(Instant start, Instant end, Long groupId) {
        String jpql = """
                SELECT COUNT(membership.id)
                  FROM GroupMembership membership, QuestAssignment assignment
                  JOIN assignment.questPeriod period
                 WHERE period.status IN ('ACTIVE', 'CLOSED')
                   AND period.startAt < :endAt
                   AND period.endAt > :startAt
                   AND membership.joinedAt < period.endAt
                   AND membership.joinedAt < :endAt
                   AND (membership.endedAt IS NULL OR membership.endedAt > period.startAt)
                   AND (membership.endedAt IS NULL OR membership.endedAt > :startAt)
                   AND NOT EXISTS (
                       SELECT 1
                         FROM QuestCompletion priorCompletion
                        WHERE priorCompletion.user = membership.user
                          AND priorCompletion.questAssignment = assignment
                          AND priorCompletion.completedAt < :startAt
                   )
                """ + groupCondition("membership.group.id", groupId);
        return number(query(jpql, groupId)
                .setParameter("startAt", start)
                .setParameter("endAt", end)
                .getSingleResult());
    }

    public long countQuestCompletions(Instant start, Instant end, Long groupId) {
        String jpql = """
                SELECT COUNT(completion.id)
                  FROM QuestCompletion completion
                  JOIN completion.membership membership
                  JOIN completion.questAssignment assignment
                  JOIN assignment.questPeriod period
                 WHERE completion.completedAt >= :startAt
                   AND completion.completedAt < :endAt
                   AND period.status IN ('ACTIVE', 'CLOSED')
                """ + groupCondition("membership.group.id", groupId);
        return number(query(jpql, groupId)
                .setParameter("startAt", start)
                .setParameter("endAt", end)
                .getSingleResult());
    }

    public Map<String, Long> growthStages(Instant now, Long groupId) {
        String jpql = """
                SELECT creature.growthStage
                     , COUNT(creature.id)
                  FROM GroupCreature creature
                  JOIN creature.groupSeason groupSeason
                  JOIN groupSeason.season season
                 WHERE season.status = 'ACTIVE'
                   AND season.startAt <= :now
                   AND season.endAt > :now
                """ + groupCondition("groupSeason.group.id", groupId) + """
                 GROUP BY creature.growthStage
                """;
        Query query = query(jpql, groupId).setParameter("now", now);
        Map<String, Long> result = new LinkedHashMap<>();
        for (Object rowValue : query.getResultList()) {
            Object[] row = (Object[]) rowValue;
            result.put(String.valueOf(row[0]), number(row[1]));
        }
        return result;
    }

    public List<Object[]> topGroups(Instant now, Long groupId) {
        String jpql = """
                SELECT gameGroup.id
                     , gameGroup.name
                     , score.score
                  FROM SeasonGroupScore score
                  JOIN score.groupSeason groupSeason
                  JOIN groupSeason.group gameGroup
                  JOIN groupSeason.season season
                 WHERE season.status = 'ACTIVE'
                   AND season.startAt <= :now
                   AND season.endAt > :now
                """ + groupCondition("gameGroup.id", groupId) + """
                 ORDER BY score.score DESC, score.scoreReachedAt, gameGroup.id
                """;
        return query(jpql, groupId).setParameter("now", now).setMaxResults(10).getResultList();
    }

    public Map<LocalDate, long[]> dailyPayments(Instant start, Instant end, Long groupId) {
        /*
         * =================================================================================================
         *  ★ 의도적으로 유지하는 Native SQL — 일반 JPQL 리팩토링 대상이 아닙니다 ★
         *
         *  UTC processed_at을 MySQL에서 KST(+09:00) 날짜로 변환하고 승인·취소 금액을 조건부 합산합니다.
         *  JPQL의 이식 가능한 함수만으로 동일한 날짜 경계와 단일 DB 집계를 보장할 수 없어 남긴 예외입니다.
         *  DB 독립적인 KST 날짜 Snapshot 컬럼이 도입되기 전에는 기계적으로 JPQL로 변경하지 않습니다.
         * =================================================================================================
         */
        String sql = """
                SELECT DATE(DATE_ADD(transaction.processed_at, INTERVAL 9 HOUR)) AS business_date
                     , SUM(CASE WHEN transaction.transaction_type = 'APPROVAL' THEN transaction.amount_krw ELSE 0 END)
                     , SUM(CASE WHEN transaction.transaction_type = 'CANCELLATION' THEN transaction.amount_krw ELSE 0 END)
                  FROM payment_transactions transaction
                  JOIN purchases purchase ON purchase.id = transaction.purchase_id
                 WHERE transaction.status = 'SUCCESS'
                   AND transaction.processed_at >= :startAt
                   AND transaction.processed_at < :endAt
                """ + groupCondition("purchase.group_id_snapshot", groupId) + """
                 GROUP BY business_date
                 ORDER BY business_date
                """;
        Query query = nativeQuery(sql, groupId)
                .setParameter("startAt", start)
                .setParameter("endAt", end);
        Map<LocalDate, long[]> result = new LinkedHashMap<>();
        for (Object rowValue : query.getResultList()) {
            Object[] row = (Object[]) rowValue;
            result.put(localDate(row[0]), new long[]{number(row[1]), number(row[2])});
        }
        return result;
    }

    public long[] capacityUsage(Long groupId) {
        String jpql = """
                SELECT COALESCE(SUM(gameGroup.currentMemberCount), 0)
                     , COALESCE(SUM(gameGroup.capacity - gameGroup.currentMemberCount), 0)
                  FROM Group gameGroup
                 WHERE gameGroup.status = 'ACTIVE'
                """ + groupCondition("gameGroup.id", groupId);
        Object[] row = (Object[]) query(jpql, groupId).getSingleResult();
        return new long[]{number(row[0]), number(row[1])};
    }

    public long countPendingReports(Long groupId) {
        String jpql = """
                SELECT COUNT(report.id)
                  FROM UserReport report
                 WHERE report.status = 'RECEIVED'
                """ + groupCondition("report.group.id", groupId);
        return number(query(jpql, groupId).getSingleResult());
    }

    public long countPendingRefunds(Long groupId) {
        String jpql = """
                SELECT COUNT(refund.id)
                  FROM RefundRequest refund
                  JOIN refund.purchase purchase
                 WHERE refund.status = 'REQUESTED'
                """ + groupCondition("purchase.groupSnapshot.id", groupId);
        return number(query(jpql, groupId).getSingleResult());
    }

    private Query query(String jpql, Long groupId) {
        Query query = entityManager.createQuery(jpql);
        if (groupId != null) {
            query.setParameter("groupId", groupId);
        }
        return query;
    }

    private Query nativeQuery(String sql, Long groupId) {
        Query query = entityManager.createNativeQuery(sql);
        if (groupId != null) {
            query.setParameter("groupId", groupId);
        }
        return query;
    }

    private String groupCondition(String column, Long groupId) {
        return groupId == null ? "" : " AND " + column + " = :groupId";
    }

    private long number(Object value) {
        return value == null ? 0L : ((Number) value).longValue();
    }

    private LocalDate localDate(Object value) {
        if (value instanceof LocalDate date) {
            return date;
        }
        if (value instanceof Date date) {
            return date.toLocalDate();
        }
        if (value instanceof Timestamp timestamp) {
            return timestamp.toLocalDateTime().toLocalDate();
        }
        return LocalDate.parse(String.valueOf(value));
    }
}
