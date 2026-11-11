package com.team.sharedcreature.admin.api;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/** 전체 관리자 대시보드의 서버 집계 결과입니다. */
public record AdminDashboardResponse(
        String range,
        Long groupId,
        Instant aggregatedAt,
        Summary summary,
        List<ActivityPoint> activitySeries,
        List<GrowthStagePoint> growthStages,
        List<TopGroupPoint> topGroups,
        List<PaymentPoint> paymentSeries,
        CapacityUsage capacityUsage,
        Queues queues
) {
    /** 요약 카드에 사용하는 서버 집계값입니다. */
    public record Summary(
            long activeUsers,
            long activeGroups,
            BigDecimal questCompletionRate,
            long paidAmountKrw,
            long refundedAmountKrw,
            BigDecimal capacityUsageRate,
            long operationalWarningCount
    ) {
    }

    /** KST 날짜별 활동과 퀘스트 완료 건수입니다. */
    public record ActivityPoint(LocalDate date, long activeUsers, long questCompletions) {
    }

    /** 현재 시즌 성장 단계별 그룹 수입니다. */
    public record GrowthStagePoint(String stage, long count) {
    }

    /** 현재 시즌 점수 상위 그룹입니다. */
    public record TopGroupPoint(Long groupId, String groupName, long score) {
    }

    /** KST 날짜별 성공 결제·환불 금액입니다. */
    public record PaymentPoint(LocalDate date, long paidAmountKrw, long refundedAmountKrw) {
    }

    /** 현재 활성 그룹 정원 사용 현황입니다. */
    public record CapacityUsage(long occupied, long remaining) {
    }

    /** 관리자 처리 대기열과 최근 운영 경고입니다. */
    public record Queues(
            int pendingReports,
            int pendingRefunds,
            List<OperationalWarningResponse> operationalWarnings
    ) {
    }
}
