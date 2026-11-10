package com.team.sharedcreature.admin.application;

import com.team.sharedcreature._core.exception.BusinessException;
import com.team.sharedcreature._core.exception.ErrorCode;
import com.team.sharedcreature.admin.api.AdminDashboardResponse;
import com.team.sharedcreature.admin.api.OperationalWarningResponse;
import com.team.sharedcreature.admin.infrastructure.AdminAnalyticsRepository;
import com.team.sharedcreature.group.infrastructure.GroupRepository;
import com.team.sharedcreature.season.infrastructure.SeasonRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** 기간·그룹 필터에 따른 관리자 대시보드 데이터를 서버에서 집계합니다. */
@Service
@Transactional(readOnly = true)
public class AdminDashboardService {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    private static final List<String> GROWTH_STAGE_ORDER =
            List.of("EGG", "HATCHLING", "GROWING", "MATURE", "COMPLETE");

    private final AdminAnalyticsRepository analyticsRepository;
    private final OperationalWarningService warningService;
    private final SeasonRepository seasonRepository;
    private final GroupRepository groupRepository;
    private final Clock clock;

    /** 관리자 집계 원본과 현재 상태 조회 Service를 주입받습니다. */
    public AdminDashboardService(
            AdminAnalyticsRepository analyticsRepository,
            OperationalWarningService warningService,
            SeasonRepository seasonRepository,
            GroupRepository groupRepository,
            Clock clock
    ) {
        this.analyticsRepository = analyticsRepository;
        this.warningService = warningService;
        this.seasonRepository = seasonRepository;
        this.groupRepository = groupRepository;
        this.clock = clock;
    }

    /** 선택 기간의 시계열과 현재 Snapshot을 하나의 응답으로 구성합니다. */
    public AdminDashboardResponse get(String range, Long groupId) {
        Instant aggregatedAt = clock.instant();
        if (groupId != null && groupRepository.findById(groupId)
                .filter(group -> "ACTIVE".equals(group.getStatus())).isEmpty()) {
            throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND);
        }
        Instant start = rangeStart(range, aggregatedAt);
        LocalDate startDate = start.atZone(KST).toLocalDate();
        LocalDate endDate = aggregatedAt.atZone(KST).toLocalDate();

        long activeUsers = analyticsRepository.countActiveUsers(startDate, endDate, groupId);
        long activeGroups = analyticsRepository.countActiveGroups(startDate, endDate, groupId);
        long opportunities = analyticsRepository.countQuestOpportunities(start, aggregatedAt, groupId);
        long completions = analyticsRepository.countQuestCompletions(start, aggregatedAt, groupId);
        BigDecimal completionRate = percentage(completions, opportunities);

        Map<LocalDate, Long> dailyUsers = analyticsRepository.dailyActiveUsers(startDate, endDate, groupId);
        Map<LocalDate, Long> dailyCompletions =
                analyticsRepository.dailyQuestCompletions(start, aggregatedAt, groupId);
        List<AdminDashboardResponse.ActivityPoint> activities = new ArrayList<>();
        for (LocalDate date = startDate; !date.isAfter(endDate); date = date.plusDays(1)) {
            activities.add(new AdminDashboardResponse.ActivityPoint(
                    date, dailyUsers.getOrDefault(date, 0L), dailyCompletions.getOrDefault(date, 0L)
            ));
        }

        Map<String, Long> growthCounts = analyticsRepository.growthStages(aggregatedAt, groupId);
        List<AdminDashboardResponse.GrowthStagePoint> growthStages = GROWTH_STAGE_ORDER.stream()
                .map(stage -> new AdminDashboardResponse.GrowthStagePoint(
                        stage, growthCounts.getOrDefault(stage, 0L)
                ))
                .toList();
        List<AdminDashboardResponse.TopGroupPoint> topGroups = analyticsRepository
                .topGroups(aggregatedAt, groupId).stream()
                .map(row -> new AdminDashboardResponse.TopGroupPoint(
                        ((Number) row[0]).longValue(), String.valueOf(row[1]), ((Number) row[2]).longValue()
                ))
                .toList();

        Map<LocalDate, long[]> dailyPayments = analyticsRepository.dailyPayments(start, aggregatedAt, groupId);
        List<AdminDashboardResponse.PaymentPoint> paymentSeries = new ArrayList<>();
        long paidAmount = 0L;
        long refundedAmount = 0L;
        for (LocalDate date = startDate; !date.isAfter(endDate); date = date.plusDays(1)) {
            long[] amounts = dailyPayments.getOrDefault(date, new long[]{0L, 0L});
            paidAmount = Math.addExact(paidAmount, amounts[0]);
            refundedAmount = Math.addExact(refundedAmount, amounts[1]);
            paymentSeries.add(new AdminDashboardResponse.PaymentPoint(date, amounts[0], amounts[1]));
        }

        long[] capacity = analyticsRepository.capacityUsage(groupId);
        BigDecimal capacityRate = percentage(capacity[0], Math.addExact(capacity[0], capacity[1]));
        List<OperationalWarningResponse> warnings = warningService.currentWarnings().stream()
                .filter(warning -> groupId == null
                        || !"GROUP".equals(warning.resourceType())
                        || groupId.equals(warning.resourceId()))
                .sorted(java.util.Comparator.comparing(OperationalWarningResponse::detectedAt).reversed())
                .toList();
        List<OperationalWarningResponse> recentWarnings = warnings.stream().limit(10).toList();

        return new AdminDashboardResponse(
                range,
                groupId,
                aggregatedAt,
                new AdminDashboardResponse.Summary(
                        activeUsers, activeGroups, completionRate, paidAmount, refundedAmount,
                        capacityRate, warnings.size()
                ),
                activities,
                growthStages,
                topGroups,
                paymentSeries,
                new AdminDashboardResponse.CapacityUsage(capacity[0], capacity[1]),
                new AdminDashboardResponse.Queues(
                        Math.toIntExact(analyticsRepository.countPendingReports(groupId)),
                        Math.toIntExact(analyticsRepository.countPendingRefunds(groupId)),
                        recentWarnings
                )
        );
    }

    private Instant rangeStart(String range, Instant now) {
        LocalDate today = now.atZone(KST).toLocalDate();
        return switch (range) {
            case "7_DAYS" -> today.minusDays(6).atStartOfDay(KST).toInstant();
            case "30_DAYS" -> today.minusDays(29).atStartOfDay(KST).toInstant();
            case "CURRENT_SEASON" -> seasonRepository.findFirstByStatusOrderByStartAtDesc("ACTIVE")
                    .filter(season -> !now.isBefore(season.getStartAt()) && now.isBefore(season.getEndAt()))
                    .orElseThrow(() -> new BusinessException(ErrorCode.CONCURRENT_MODIFICATION))
                    .getStartAt();
            default -> throw new BusinessException(ErrorCode.INVALID_FIELD_VALUE);
        };
    }

    private BigDecimal percentage(long numerator, long denominator) {
        if (denominator == 0L) {
            return BigDecimal.ZERO.setScale(2);
        }
        return BigDecimal.valueOf(numerator)
                .multiply(BigDecimal.valueOf(100))
                .divide(BigDecimal.valueOf(denominator), 2, RoundingMode.HALF_UP);
    }
}
