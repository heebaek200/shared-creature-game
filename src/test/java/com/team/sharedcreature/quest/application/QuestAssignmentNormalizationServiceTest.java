package com.team.sharedcreature.quest.application;

import com.team.sharedcreature.admin.application.SchedulerJobRunner;
import com.team.sharedcreature.quest.infrastructure.QuestPeriodRepository;
import com.team.sharedcreature.season.domain.Season;
import com.team.sharedcreature.season.infrastructure.SeasonRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentMatchers;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * 서버 기동·자정·5분 정상화에서 현재 기간만 선점하는지 확인합니다.
 * 과거 기간을 생성하지 않고 실행 결과 요약에 누락 기간을 기록합니다.
 * 외부 DB 없이 업무 콜백과 실행권 관리 호출 순서를 검증합니다.
 */
@ExtendWith(MockitoExtension.class)
class QuestAssignmentNormalizationServiceTest {

    private static final Instant FIRST = Instant.parse("2026-11-01T15:00:00Z");
    private static final Instant NOW = Instant.parse("2026-11-09T03:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

    @Mock private SeasonRepository seasons;
    @Mock private QuestPeriodRepository periods;
    @Mock private QuestPeriodAssignmentService assignments;
    @Mock private SchedulerJobRunner jobRunner;
    @Mock private Season active;
    @Mock private Season first;

    private QuestAssignmentNormalizationService normalizer;
    private final List<String> summaries = new ArrayList<>();

    /**
     * 순수 기간 계산을 사용하고 DB·실행권은 Mock으로 대체합니다.
     * 현재 시각은 KST 2026년 11월 9일 월요일 정오입니다.
     * 모든 테스트는 새 정합화 서비스를 사용해 상태가 섞이지 않도록 합니다.
     */
    @BeforeEach
    void setUp() {
        normalizer = new QuestAssignmentNormalizationService(
                seasons, periods, new QuestPeriodCalendar(), assignments, jobRunner, CLOCK);
    }

    /**
     * 월요일 시작의 현재 일일·주간을 모두 논리 시각 기준으로 실행합니다.
     * 같은 날 5분 뒤 다시 호출하더라도 기간 시작 시각은 변하지 않습니다.
     * 실제 작업을 마친 뒤 반환된 JSON 요약만 성공으로 처리합니다.
     */
    @Test
    void schedulesCurrentDailyAndWeeklyWithLogicalStart() {
        allowActiveSeason();
        mockSuccessfulExecution();
        mockCreatedAssignments();

        var result = normalizer.normalizeForActiveSeason(7L);
        assertEquals(QuestAssignmentNormalizationService.Outcome.CHECKED, result.outcome());
        assertEquals(2, result.periods().size());
        assertEquals(SchedulerJobRunner.ExecutionResult.SUCCEEDED, result.periods().get(0).execution());
        assertEquals(SchedulerJobRunner.ExecutionResult.SUCCEEDED, result.periods().get(1).execution());

        // 월요일 자정에는 일일·주간 기간 모두 같은 논리 시작 시각을 사용합니다.
        Instant monday = Instant.parse("2026-11-08T15:00:00Z");
        assertEquals(monday, result.periods().get(0).startAt());
        assertEquals(monday, result.periods().get(1).startAt());
        verify(jobRunner).run(eq("QUEST_ASSIGN_DAILY"), eq(monday), anyString(),
                eq(Duration.ofMinutes(3)), ArgumentMatchers.<Supplier<String>>any());
        verify(jobRunner).run(eq("QUEST_ASSIGN_WEEKLY"), eq(monday), anyString(),
                eq(Duration.ofMinutes(3)), ArgumentMatchers.<Supplier<String>>any());
        assertEquals(2, summaries.size());
    }

    /**
     * DB에 저장된 이전 일일 기간을 제외하고 실제 누락 건수를 집계합니다.
     * 월요일의 이전 일일 기간은 7개이며 3개가 기록되었으므로 4개를 생략했습니다.
     * 누락된 시작·마지막 날짜도 실행 이력 요약에 기록하는지 확인합니다.
     */
    @Test
    void summarizesOmittedExpiredPeriodsWithoutBackfill() {
        allowActiveSeason();
        mockSuccessfulExecution();
        mockCreatedAssignments();
        Instant monday = Instant.parse("2026-11-08T15:00:00Z");
        when(periods.findHistoricalStartAts(eq("DAILY"), eq(FIRST), eq(monday)))
                .thenReturn(List.of(FIRST, FIRST.plus(Duration.ofDays(2)), FIRST.plus(Duration.ofDays(4))));

        var result = normalizer.normalizeForActiveSeason(7L);
        assertEquals(4L, result.periods().get(0).skippedExpiredPeriods());
        assertEquals(1L, result.periods().get(1).skippedExpiredPeriods());
        assertTrue(summaries.get(0).contains("\"skippedExpiredCount\":4"));
        assertTrue(summaries.get(0).contains("\"skippedFrom\":\"2026-11-02T15:00:00Z\""));
        assertTrue(summaries.get(0).contains("\"skippedThrough\":\"2026-11-07T15:00:00Z\""));
        assertTrue(summaries.get(1).contains("\"skippedExpiredCount\":1"));
        verify(assignments, times(2)).assign(any());
    }

    /**
     * 템플릿 부족으로 FAILED가 반환되면 성공 이력으로 승격하지 않습니다.
     * 실패한 DAILY와 독립적으로 WEEKLY의 정상 배정은 계속 수행합니다.
     * 다음 5분 점검에서 Scheduler FAILED 실행권을 다시 선점할 수 있어야 합니다.
     */
    @Test
    void failedDailyDoesNotStopWeeklyAndCannotSucceedScheduler() {
        allowActiveSeason();
        mockSuccessfulExecution();
        when(assignments.assign(any())).thenAnswer(invocation -> {
            QuestPeriodCalendar.PeriodWindow w = invocation.getArgument(0);
            if ("DAILY".equals(w.periodType())) {
                return new QuestPeriodAssignmentService.Result(
                        QuestPeriodAssignmentService.Outcome.FAILED, 10L, 0);
            }
            return new QuestPeriodAssignmentService.Result(
                    QuestPeriodAssignmentService.Outcome.CREATED, 11L, 7);
        });

        var result = normalizer.normalizeForActiveSeason(7L);
        assertNotNull(result.periods().get(0).error());
        assertNull(result.periods().get(0).execution());
        assertEquals(SchedulerJobRunner.ExecutionResult.SUCCEEDED, result.periods().get(1).execution());
        verify(assignments, times(2)).assign(any());
        assertEquals(1, summaries.size());
        assertTrue(summaries.get(0).contains("WEEKLY"));
    }

    /**
     * 같은 기간 실행권을 다른 서버가 선점했다면 업무 배정을 호출하지 않습니다.
     * SUCCEEDED 또는 유효 RUNNING 기록은 공통 JobRunner가 SKIPPED로 반환합니다.
     * 중복 Cron을 별도 Scheduler로 만들 필요가 없음을 검증합니다.
     */
    @Test
    void skippedClaimsNeverExecuteAssignment() {
        allowActiveSeason();
        when(jobRunner.run(anyString(), any(Instant.class), anyString(), any(Duration.class),
                ArgumentMatchers.<Supplier<String>>any()))
                .thenReturn(SchedulerJobRunner.ExecutionResult.SKIPPED);

        var result = normalizer.normalizeForActiveSeason(7L);
        assertEquals(SchedulerJobRunner.ExecutionResult.SKIPPED, result.periods().get(0).execution());
        assertEquals(SchedulerJobRunner.ExecutionResult.SKIPPED, result.periods().get(1).execution());
        verifyNoInteractions(assignments);
    }

    /**
     * 시즌이 ACTIVE가 아니면 Scheduler 실행권조차 획득하지 않습니다.
     * 부분 초기화 시즌은 기존 시즌 정상화가 먼저 완료되어야 합니다.
     * 미완료 기간이 나중에 보상으로 소급 생성되는 것도 방지합니다.
     */
    @Test
    void doesNothingWhenSeasonIsNotReady() {
        when(seasons.findById(7L)).thenReturn(Optional.of(active));
        when(active.isActiveAt(NOW)).thenReturn(false);
        var result = normalizer.normalizeForActiveSeason(7L);
        assertEquals(QuestAssignmentNormalizationService.Outcome.NOT_READY, result.outcome());
        verifyNoInteractions(jobRunner, periods, assignments);
    }

    /**
     * 기존 호출자가 트랜잭션을 열었다면 작업권을 선점하기 전에 중단합니다.
     * 실행 이력 성공 기록이 업무 Commit보다 먼저 되는 일을 차단합니다.
     * 테스트 종료 후 스레드의 트랜잭션 표시는 원래 상태로 복원합니다.
     */
    @Test
    void rejectsExistingTransaction() {
        boolean original = TransactionSynchronizationManager.isActualTransactionActive();
        try {
            TransactionSynchronizationManager.setActualTransactionActive(true);
            assertThrows(IllegalStateException.class, () -> normalizer.normalizeForActiveSeason(7L));
            verifyNoInteractions(seasons, jobRunner, assignments);
        } finally {
            TransactionSynchronizationManager.setActualTransactionActive(original);
        }
    }

    /**
     * 정상 ACTIVE 시즌과 첫 시즌 시작을 조회하도록 Mock을 설정합니다.
     * 현재 시각이 현재 시즌 안에 있다는 업무 판단만 여기서 설정합니다.
     * 기록되지 않은 퀘스트 기간은 Repository가 기본 빈 목록으로 반환합니다.
     */
    private void allowActiveSeason() {
        when(seasons.findById(7L)).thenReturn(Optional.of(active));
        when(active.isActiveAt(NOW)).thenReturn(true);
        when(seasons.findFirstByOrderByStartAtAsc()).thenReturn(Optional.of(first));
        when(first.getStartAt()).thenReturn(FIRST);
        when(periods.findHistoricalStartAts(anyString(), eq(FIRST), any(Instant.class)))
                .thenReturn(List.of());
    }

    /**
     * 실제 Runner가 내부 Supplier를 실행하는 동작을 Mock으로 대신합니다.
     * Supplier가 예외를 던지면 성공 요약을 저장하지 않고 예외를 유지합니다.
     * 반환된 JSON을 저장해 기간 및 생략 정보의 정확성을 검증합니다.
     */
    private void mockSuccessfulExecution() {
        when(jobRunner.run(anyString(), any(Instant.class), anyString(), any(Duration.class),
                ArgumentMatchers.<Supplier<String>>any()))
                .thenAnswer(invocation -> {
                    Supplier<String> callback = invocation.getArgument(4);
                    summaries.add(callback.get());
                    return SchedulerJobRunner.ExecutionResult.SUCCEEDED;
                });
    }

    /**
     * 일일은 3개, 주간은 7개의 Snapshot이 Commit된 상황을 모사합니다.
     * 실제 카탈로그 선정 및 DB INSERT는 앞선 4-F-2/3 테스트가 보장합니다.
     * 기간 정상화에서 필요한 성공 결과만 만들어 전달합니다.
     */
    private void mockCreatedAssignments() {
        when(assignments.assign(any())).thenAnswer(invocation -> {
            QuestPeriodCalendar.PeriodWindow window = invocation.getArgument(0);
            boolean daily = "DAILY".equals(window.periodType());
            return new QuestPeriodAssignmentService.Result(
                    QuestPeriodAssignmentService.Outcome.CREATED, daily ? 10L : 11L, daily ? 3L : 7L);
        });
    }
}
