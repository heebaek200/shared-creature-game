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

    /** 집계 SQL 실행에 사용할 영속성 Context를 주입받습니다. */
    public AdminAnalyticsRepository(EntityManager entityManager) {
        this.entityManager = entityManager;
    }

    public long countActiveUsers(LocalDate startDate, LocalDate endDate, Long groupId) {
        String sql = """
                SELECT COUNT(DISTINCT activity.user_id)
                  FROM user_daily_activities activity
                 WHERE activity.business_date >= :startDate
                   AND activity.business_date <= :endDate
                """ + groupCondition("activity.group_id_snapshot", groupId);
        return number(query(sql, groupId)
                .setParameter("startDate", startDate)
                .setParameter("endDate", endDate)
                .getSingleResult());
    }

    public long countActiveGroups(LocalDate startDate, LocalDate endDate, Long groupId) {
        String sql = """
                SELECT COUNT(DISTINCT activity.group_id_snapshot)
                  FROM user_daily_activities activity
                 WHERE activity.business_date >= :startDate
                   AND activity.business_date <= :endDate
                   AND activity.group_id_snapshot IS NOT NULL
                """ + groupCondition("activity.group_id_snapshot", groupId);
        return number(query(sql, groupId)
                .setParameter("startDate", startDate)
                .setParameter("endDate", endDate)
                .getSingleResult());
    }

    public Map<LocalDate, Long> dailyActiveUsers(LocalDate startDate, LocalDate endDate, Long groupId) {
        String sql = """
                SELECT activity.business_date
                     , COUNT(DISTINCT activity.user_id)
                  FROM user_daily_activities activity
                 WHERE activity.business_date >= :startDate
                   AND activity.business_date <= :endDate
                """ + groupCondition("activity.group_id_snapshot", groupId) + """
                 GROUP BY activity.business_date
                 ORDER BY activity.business_date
                """;
        Query query = query(sql, groupId)
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
        Query query = query(sql, groupId)
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
        String sql = """
                SELECT COUNT(*)
                  FROM group_memberships membership
                 CROSS JOIN quest_assignments assignment
                  JOIN quest_periods period ON period.id = assignment.quest_period_id
                 WHERE period.status IN ('ACTIVE', 'CLOSED')
                   AND period.start_at < :endAt
                   AND period.end_at > :startAt
                   AND membership.joined_at < LEAST(period.end_at, :endAt)
                   AND COALESCE(membership.ended_at, :endAt) > GREATEST(period.start_at, :startAt)
                   AND NOT EXISTS (
                       SELECT 1
                         FROM quest_completions prior_completion
                        WHERE prior_completion.user_id = membership.user_id
                          AND prior_completion.quest_assignment_id = assignment.id
                          AND prior_completion.completed_at < :startAt
                   )
                """ + groupCondition("membership.group_id", groupId);
        return number(query(sql, groupId)
                .setParameter("startAt", start)
                .setParameter("endAt", end)
                .getSingleResult());
    }

    public long countQuestCompletions(Instant start, Instant end, Long groupId) {
        String sql = """
                SELECT COUNT(completion.id)
                  FROM quest_completions completion
                  JOIN group_memberships membership ON membership.id = completion.membership_id
                  JOIN quest_assignments assignment ON assignment.id = completion.quest_assignment_id
                  JOIN quest_periods period ON period.id = assignment.quest_period_id
                 WHERE completion.completed_at >= :startAt
                   AND completion.completed_at < :endAt
                   AND period.status IN ('ACTIVE', 'CLOSED')
                """ + groupCondition("membership.group_id", groupId);
        return number(query(sql, groupId)
                .setParameter("startAt", start)
                .setParameter("endAt", end)
                .getSingleResult());
    }

    public Map<String, Long> growthStages(Instant now, Long groupId) {
        String sql = """
                SELECT creature.growth_stage
                     , COUNT(creature.id)
                  FROM group_creatures creature
                  JOIN group_seasons group_season ON group_season.id = creature.group_season_id
                  JOIN seasons season ON season.id = group_season.season_id
                 WHERE season.status = 'ACTIVE'
                   AND season.start_at <= :now
                   AND season.end_at > :now
                """ + groupCondition("group_season.group_id", groupId) + """
                 GROUP BY creature.growth_stage
                """;
        Query query = query(sql, groupId).setParameter("now", now);
        Map<String, Long> result = new LinkedHashMap<>();
        for (Object rowValue : query.getResultList()) {
            Object[] row = (Object[]) rowValue;
            result.put(String.valueOf(row[0]), number(row[1]));
        }
        return result;
    }

    public List<Object[]> topGroups(Instant now, Long groupId) {
        String sql = """
                SELECT game_group.id
                     , game_group.name
                     , score.score
                  FROM season_group_scores score
                  JOIN group_seasons group_season ON group_season.id = score.group_season_id
                  JOIN game_groups game_group ON game_group.id = group_season.group_id
                  JOIN seasons season ON season.id = group_season.season_id
                 WHERE season.status = 'ACTIVE'
                   AND season.start_at <= :now
                   AND season.end_at > :now
                """ + groupCondition("game_group.id", groupId) + """
                 ORDER BY score.score DESC, score.score_reached_at, game_group.id
                 LIMIT 10
                """;
        return query(sql, groupId).setParameter("now", now).getResultList();
    }

    public Map<LocalDate, long[]> dailyPayments(Instant start, Instant end, Long groupId) {
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
        Query query = query(sql, groupId)
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
        String sql = """
                SELECT COALESCE(SUM(game_group.current_member_count), 0)
                     , COALESCE(SUM(game_group.capacity - game_group.current_member_count), 0)
                  FROM game_groups game_group
                 WHERE game_group.status = 'ACTIVE'
                """ + groupCondition("game_group.id", groupId);
        Object[] row = (Object[]) query(sql, groupId).getSingleResult();
        return new long[]{number(row[0]), number(row[1])};
    }

    public long countPendingReports(Long groupId) {
        String sql = """
                SELECT COUNT(report.id)
                  FROM user_reports report
                 WHERE report.status = 'RECEIVED'
                """ + groupCondition("report.group_id", groupId);
        return number(query(sql, groupId).getSingleResult());
    }

    public long countPendingRefunds(Long groupId) {
        String sql = """
                SELECT COUNT(refund.id)
                  FROM refund_requests refund
                  JOIN purchases purchase ON purchase.id = refund.purchase_id
                 WHERE refund.status = 'REQUESTED'
                """ + groupCondition("purchase.group_id_snapshot", groupId);
        return number(query(sql, groupId).getSingleResult());
    }

    private Query query(String sql, Long groupId) {
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
