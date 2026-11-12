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
 * 현재 퀘스트 기간 정상화·실행권 조회·Snapshot 검증 경계를 테스트합니다.
 * SUCCEEDED 이력이 깨진 상태에서도 SKIPPED로 조용히 종료하지 않아야 합니다.
 * 실제 DB의 상태 비교는 별도 MySQL 통합 테스트에서 확인합니다.
 */
@ExtendWith(MockitoExtension.class)
class QuestAssignmentNormalizationServiceTest {

    private static final Instant FIRST = Instant.parse("2026-11-01T15:00:00Z");
    private static final Instant NOW = Instant.parse("2026-11-09T03:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

    @Mock private SeasonRepository seasons;
    @Mock private QuestPeriodRepository periods;
    @Mock private QuestPeriodAssignmentService assignments;
    @Mock private QuestAssignmentIntegrityService integrity;
    @Mock private SchedulerJobRunner jobRunner;
    @Mock private Season active;
    @Mock private Season first;

    private QuestAssignmentNormalizationService normalizer;
    private final List<String> summaries = new ArrayList<>();

    /**
     * 테스트마다 정상화 서비스를 새로 구성합니다.
     * 시간은 KST 2026년 11월 9일 월요일 정오로 고정합니다.
     * Scheduler·Repository·정합성 조회는 Mock을 사용합니다.
     */
    @BeforeEach
    void setUp() {
        normalizer = new QuestAssignmentNormalizationService(
                seasons, periods, new QuestPeriodCalendar(), assignments,
                integrity, jobRunner, CLOCK);
    }

    /**
     * 현재 월요일의 일일·주간 기간을 논리 시작 시각으로 선점합니다.
     * 성공 요약을 반환하기 전에 Snapshot 검사가 호출되어야 합니다.
     * 과거 구간은 추가 생성하지 않습니다.
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

        // 두 종류의 퀘스트 모두 월요일 00:00 KST가 논리 시작점입니다.
        Instant monday = Instant.parse("2026-11-08T15:00:00Z");
        assertEquals(monday, result.periods().get(0).startAt());
        assertEquals(monday, result.periods().get(1).startAt());
        verify(jobRunner).run(eq("QUEST_ASSIGN_DAILY"), eq(monday), anyString(),
                eq(Duration.ofMinutes(3)), ArgumentMatchers.<Supplier<String>>any());
        verify(jobRunner).run(eq("QUEST_ASSIGN_WEEKLY"), eq(monday), anyString(),
                eq(Duration.ofMinutes(3)), ArgumentMatchers.<Supplier<String>>any());
        verify(integrity, times(2)).verifyCommittedAssignment(any(), eq(7L));
        assertEquals(2, summaries.size());
    }

    /**
     * 누락된 이전 일일 기간을 정확히 계산해 Scheduler 결과에 남깁니다.
     * 일일 기록 3개를 제외한 4개를 누락으로 계산합니다.
     * 주간 1개 누락도 별도로 요약합니다.
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
     * DAILY 선정 실패는 Scheduler 성공 이력으로 처리하면 안 됩니다.
     * WEEKLY는 다른 실행권이므로 중단 없이 계속됩니다.
     * 실패한 DAILY는 후속 5분 정상화에서 재시도됩니다.
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
     * 다른 서버가 RUNNING 중이라면 업무 배정은 재호출하지 않습니다.
     * SKIPPED 후 상태 감사를 실시하되 정상 경합을 오류로 분류하지 않습니다.
     * 다음 Cron이 완료 상태를 다시 확인할 수 있습니다.
     */
    @Test
    void skippedClaimsNeverExecuteAssignment() {
        allowActiveSeason();
        mockSkippedExecution();
        when(integrity.inspectSkippedExecution(anyString(), any(), eq(7L)))
                .thenReturn(new QuestAssignmentIntegrityService.ExecutionAudit(
                        QuestAssignmentIntegrityService.AuditOutcome.NOT_SUCCEEDED, null));

        var result = normalizer.normalizeForActiveSeason(7L);
        assertEquals(SchedulerJobRunner.ExecutionResult.SKIPPED, result.periods().get(0).execution());
        assertEquals(SchedulerJobRunner.ExecutionResult.SKIPPED, result.periods().get(1).execution());
        assertNull(result.periods().get(0).error());
        assertNull(result.periods().get(1).error());
        verify(integrity, times(2)).inspectSkippedExecution(anyString(), any(), eq(7L));
        verifyNoInteractions(assignments);
    }

    /**
     * 배정 완료 이력 SUCCEEDED인데 DAILY 기간이 누락된 경우를 가정합니다.
     * 실행권은 SKIPPED라도 오류 문구를 PeriodRun으로 전달해야 합니다.
     * WEEKLY는 독립 실행으로 정상 처리합니다.
     */
    @Test
    void corruptSucceededDailyIsReportedEvenWhenSkipped() {
        allowActiveSeason();
        mockSkippedExecution();
        when(integrity.inspectSkippedExecution(anyString(), any(), eq(7L)))
                .thenAnswer(invocation -> {
                    String jobType = invocation.getArgument(0);
                    return "QUEST_ASSIGN_DAILY".equals(jobType)
                            ? new QuestAssignmentIntegrityService.ExecutionAudit(
                                    QuestAssignmentIntegrityService.AuditOutcome.INCONSISTENT,
                                    "[QUEST_ASSIGNMENT_INTEGRITY] code=PERIOD_MISSING")
                            : new QuestAssignmentIntegrityService.ExecutionAudit(
                                    QuestAssignmentIntegrityService.AuditOutcome.CONSISTENT, null);
                });
        var result = normalizer.normalizeForActiveSeason(7L);
        assertEquals(SchedulerJobRunner.ExecutionResult.SKIPPED, result.periods().get(0).execution());
        assertTrue(result.periods().get(0).error().contains("PERIOD_MISSING"));
        assertNull(result.periods().get(1).error());
        verifyNoInteractions(assignments);
    }

    /**
     * 신규 실행으로 이미 Commit된 배정이 불완전한 경우를 검증합니다.
     * 정상화 콜백은 예외를 일으켜 Scheduler 성공으로 기록하지 않습니다.
     * WEEKLY 배정은 정상적으로 완료해야 합니다.
     */
    @Test
    void incompleteCommittedDailyCannotBeMarkedSucceeded() {
        allowActiveSeason();
        mockCreatedAssignments();
        when(integrity.verifyCommittedAssignment(any(), eq(7L))).thenAnswer(invocation -> {
            QuestPeriodCalendar.PeriodWindow window = invocation.getArgument(0);
            return "DAILY".equals(window.periodType())
                    ? new QuestAssignmentIntegrityService.Validation(false,
                            "[QUEST_ASSIGNMENT_INTEGRITY] code=ASSIGNMENT_COUNT_MISMATCH")
                    : new QuestAssignmentIntegrityService.Validation(true, null);
        });
        when(jobRunner.run(anyString(), any(Instant.class), anyString(), any(Duration.class),
                ArgumentMatchers.<Supplier<String>>any()))
                .thenAnswer(invocation -> {
                    Supplier<String> callback = invocation.getArgument(4);
                    summaries.add(callback.get());
                    return SchedulerJobRunner.ExecutionResult.SUCCEEDED;
                });

        var result = normalizer.normalizeForActiveSeason(7L);
        assertTrue(result.periods().get(0).error().contains("ASSIGNMENT_COUNT_MISMATCH"));
        assertNull(result.periods().get(0).execution());
        assertEquals(SchedulerJobRunner.ExecutionResult.SUCCEEDED, result.periods().get(1).execution());
        assertEquals(1, summaries.size());
    }

    /**
     * 시즌이 ACTIVE가 아니라면 기간과 실행권도 생성하지 않습니다.
     * 부분 초기화 시즌은 기존 시즌 정상화가 먼저 완료되어야 합니다.
     * 오탐 검사를 방지하기 위해 감사 Service도 호출하지 않습니다.
     */
    @Test
    void doesNothingWhenSeasonIsNotReady() {
        when(seasons.findById(7L)).thenReturn(Optional.of(active));
        when(active.isActiveAt(NOW)).thenReturn(false);
        var result = normalizer.normalizeForActiveSeason(7L);
        assertEquals(QuestAssignmentNormalizationService.Outcome.NOT_READY, result.outcome());
        verifyNoInteractions(jobRunner, periods, assignments, integrity);
    }

    /**
     * 외부 업무 트랜잭션에서 Runner를 시작하는 것을 거절합니다.
     * 기존 트랜잭션과 Scheduler 결과 Commit의 선후관계를 보호합니다.
     * 테스트 이후 스레드의 트랜잭션 표시를 반드시 복원합니다.
     */
    @Test
    void rejectsExistingTransaction() {
        boolean original = TransactionSynchronizationManager.isActualTransactionActive();
        try {
            TransactionSynchronizationManager.setActualTransactionActive(true);
            assertThrows(IllegalStateException.class, () -> normalizer.normalizeForActiveSeason(7L));
            verifyNoInteractions(seasons, jobRunner, assignments, integrity);
        } finally {
            TransactionSynchronizationManager.setActualTransactionActive(original);
        }
    }

    /**
     * 현재 시각에 유효한 시즌과 최초 시즌의 달력을 Mock으로 준비합니다.
     * 지난 기간 목록은 빈 데이터로 반환하고 별도 테스트에서 덮어씁니다.
     * 실제 DB 쓰기 작업 없이 Scheduler 흐름을 검증합니다.
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
     * 실제 Runner처럼 내부 Supplier를 실행하고 JSON 결과를 기록합니다.
     * 배정 Commit 검증은 정상 결과로 설정합니다.
     * DAILY 실패 시에는 Runner가 성공 이력을 기록하지 못하도록 예외를 전파합니다.
     */
    private void mockSuccessfulExecution() {
        lenient().when(integrity.verifyCommittedAssignment(any(), eq(7L)))
                .thenReturn(new QuestAssignmentIntegrityService.Validation(true, null));
        when(jobRunner.run(anyString(), any(Instant.class), anyString(), any(Duration.class),
                ArgumentMatchers.<Supplier<String>>any()))
                .thenAnswer(invocation -> {
                    Supplier<String> callback = invocation.getArgument(4);
                    summaries.add(callback.get());
                    return SchedulerJobRunner.ExecutionResult.SUCCEEDED;
                });
    }

    /**
     * 두 개의 실행권 모두 이미 선점된 상황을 설정합니다.
     * 실제 JobRunner는 업무 Supplier를 실행하지 않고 SKIPPED를 반환합니다.
     * 감사 Service에서 이력 상태를 분리하여 확인하게 됩니다.
     */
    private void mockSkippedExecution() {
        when(jobRunner.run(anyString(), any(Instant.class), anyString(), any(Duration.class),
                ArgumentMatchers.<Supplier<String>>any()))
                .thenReturn(SchedulerJobRunner.ExecutionResult.SKIPPED);
    }

    /**
     * DAILY 3개와 WEEKLY 7개를 Commit한 배정 결과로 응답합니다.
     * 실제 Snapshot 개수 검사 결과는 IntegrityService에서 반환합니다.
     * 정상화 Service는 이미 완료된 DB 트랜잭션을 직접 수정하지 않습니다.
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
