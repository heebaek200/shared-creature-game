package com.team.sharedcreature.admin.application;

import com.team.sharedcreature.admin.infrastructure.AdminAnalyticsRepository;
import com.team.sharedcreature.admin.api.OperationalWarningResponse;
import com.team.sharedcreature.group.domain.Group;
import com.team.sharedcreature.group.infrastructure.GroupRepository;
import com.team.sharedcreature.season.infrastructure.SeasonRepository;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AdminDashboardServiceTest {

    @Test
    void 서버_집계값으로_완료율_정원율과_금액_요약을_구성한다() {
        AdminAnalyticsRepository analytics = mock(AdminAnalyticsRepository.class);
        OperationalWarningService warnings = mock(OperationalWarningService.class);
        SeasonRepository seasons = mock(SeasonRepository.class);
        GroupRepository groups = mock(GroupRepository.class);
        Instant now = Instant.parse("2026-11-10T03:00:00Z");
        when(analytics.countActiveUsers(any(), any(), isNull())).thenReturn(8L);
        when(analytics.countActiveGroups(any(), any(), isNull())).thenReturn(3L);
        when(analytics.countQuestOpportunities(any(), any(), isNull())).thenReturn(10L);
        when(analytics.countQuestCompletions(any(), any(), isNull())).thenReturn(5L);
        when(analytics.dailyActiveUsers(any(), any(), isNull())).thenReturn(Map.of());
        when(analytics.dailyQuestCompletions(any(), any(), isNull())).thenReturn(Map.of());
        when(analytics.growthStages(any(), isNull())).thenReturn(Map.of());
        when(analytics.topGroups(any(), isNull())).thenReturn(List.of());
        when(analytics.dailyPayments(any(), any(), isNull())).thenReturn(Map.of(
                LocalDate.of(2026, 11, 10), new long[]{3000L, 1000L}
        ));
        when(analytics.capacityUsage(isNull())).thenReturn(new long[]{5L, 15L});
        when(analytics.countPendingReports(isNull())).thenReturn(2L);
        when(analytics.countPendingRefunds(isNull())).thenReturn(1L);
        when(warnings.currentWarnings()).thenReturn(List.of());
        AdminDashboardService service = new AdminDashboardService(
                analytics, warnings, seasons, groups, Clock.fixed(now, ZoneOffset.UTC)
        );

        var response = service.get("7_DAYS", null);

        assertThat(response.summary().questCompletionRate()).isEqualByComparingTo("50.00");
        assertThat(response.summary().capacityUsageRate()).isEqualByComparingTo("25.00");
        assertThat(response.summary().paidAmountKrw()).isEqualTo(3000L);
        assertThat(response.summary().refundedAmountKrw()).isEqualTo(1000L);
        assertThat(response.activitySeries()).hasSize(7);
        assertThat(response.queues().pendingReports()).isEqualTo(2);
    }

    @Test
    void 그룹_필터를_선택해도_운영_경고는_전역으로_반환한다() {
        AdminAnalyticsRepository analytics = mock(AdminAnalyticsRepository.class);
        OperationalWarningService warnings = mock(OperationalWarningService.class);
        SeasonRepository seasons = mock(SeasonRepository.class);
        GroupRepository groups = mock(GroupRepository.class);
        Group selectedGroup = mock(Group.class);
        Instant now = Instant.parse("2026-11-10T03:00:00Z");
        when(selectedGroup.getStatus()).thenReturn("ACTIVE");
        when(groups.findById(10L)).thenReturn(Optional.of(selectedGroup));
        when(analytics.countActiveUsers(any(), any(), eq(10L))).thenReturn(0L);
        when(analytics.countActiveGroups(any(), any(), eq(10L))).thenReturn(0L);
        when(analytics.countQuestOpportunities(any(), any(), eq(10L))).thenReturn(0L);
        when(analytics.countQuestCompletions(any(), any(), eq(10L))).thenReturn(0L);
        when(analytics.dailyActiveUsers(any(), any(), eq(10L))).thenReturn(Map.of());
        when(analytics.dailyQuestCompletions(any(), any(), eq(10L))).thenReturn(Map.of());
        when(analytics.growthStages(any(), eq(10L))).thenReturn(Map.of());
        when(analytics.topGroups(any(), eq(10L))).thenReturn(List.of());
        when(analytics.dailyPayments(any(), any(), eq(10L))).thenReturn(Map.of());
        when(analytics.capacityUsage(10L)).thenReturn(new long[]{0L, 20L});
        when(analytics.countPendingReports(10L)).thenReturn(0L);
        when(analytics.countPendingRefunds(10L)).thenReturn(0L);
        OperationalWarningResponse otherGroupWarning = new OperationalWarningResponse(
                "MEMBER_COUNT_MISMATCH:GROUP:20",
                "MEMBER_COUNT_MISMATCH",
                "WARNING",
                "GROUP",
                20L,
                "다른 그룹 경고",
                now.minusSeconds(60)
        );
        when(warnings.currentWarnings()).thenReturn(List.of(otherGroupWarning));
        AdminDashboardService service = new AdminDashboardService(
                analytics, warnings, seasons, groups, Clock.fixed(now, ZoneOffset.UTC)
        );

        var response = service.get("7_DAYS", 10L);

        assertThat(response.summary().operationalWarningCount()).isOne();
        assertThat(response.queues().operationalWarnings()).containsExactly(otherGroupWarning);
    }
}
