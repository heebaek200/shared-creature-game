package com.team.sharedcreature.quest.application;

import com.team.sharedcreature.admin.domain.SchedulerExecution;
import com.team.sharedcreature.admin.infrastructure.SchedulerExecutionRepository;
import com.team.sharedcreature.quest.domain.QuestAssignment;
import com.team.sharedcreature.quest.domain.QuestPeriod;
import com.team.sharedcreature.quest.domain.QuestTemplate;
import com.team.sharedcreature.quest.domain.QuestTemplateVersion;
import com.team.sharedcreature.quest.infrastructure.QuestAssignmentRepository;
import com.team.sharedcreature.quest.infrastructure.QuestPeriodRepository;
import com.team.sharedcreature.season.domain.Season;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * R-3 Scheduler 실행 이력과 Snapshot의 기본·확장 정합성 검사를 검증합니다.
 * DAILY 1/1/1과 WEEKLY 3/2/2 슬롯 구성, 버전 소속 FK를 함께 확인합니다.
 * Mockito stubbing 중에 다른 Mock을 생성·설정하지 않도록 Fixture를 먼저 구성합니다.
 */
class QuestAssignmentIntegrityServiceTest {

    private static final Instant START = Instant.parse("2026-11-09T15:00:00Z");
    private static final QuestPeriodCalendar.PeriodWindow WINDOW =
            new QuestPeriodCalendar.PeriodWindow("DAILY", LocalDate.of(2026, 11, 10),
                    START, START.plusSeconds(86400), 8);
    private static final QuestPeriodCalendar.PeriodWindow WEEKLY_WINDOW =
            new QuestPeriodCalendar.PeriodWindow("WEEKLY", LocalDate.of(2026, 11, 9),
                    Instant.parse("2026-11-08T15:00:00Z"),
                    Instant.parse("2026-11-15T15:00:00Z"), 1);
    private static final Long SEASON_ID = 7L;
    private static final String JOB = "QUEST_ASSIGN_DAILY";

    private final SchedulerExecutionRepository executions = mock(SchedulerExecutionRepository.class);
    private final QuestPeriodRepository periods = mock(QuestPeriodRepository.class);
    private final QuestAssignmentRepository assignments = mock(QuestAssignmentRepository.class);
    private final QuestAssignmentIntegrityService service =
            new QuestAssignmentIntegrityService(executions, periods, assignments);

    /**
     * RUNNING 상태를 확정 성공과 구분합니다.
     * 경쟁 실행자가 아직 Snapshot을 저장 중일 수 있습니다.
     * 해당 실행에서는 기간이나 배정 상태를 조회하지 않습니다.
     */
    @Test
    void runningExecutionDoesNotReportFalseCorruption() {
        allowExecution("RUNNING");
        var audit = service.inspectSkippedExecution(JOB, WINDOW, SEASON_ID);
        assertEquals(QuestAssignmentIntegrityService.AuditOutcome.NOT_SUCCEEDED, audit.outcome());
        assertNull(audit.detail());
        verifyNoInteractions(periods, assignments);
    }

    /**
     * FAILED 상태는 재선점을 기다리는 상태로 해석합니다.
     * 이력이 SUCCEEDED가 아니므로 데이터 누락을 곧바로 오류로 확정하지 않습니다.
     * 읽기 전용 검사는 실패 이력을 수정하지 않습니다.
     */
    @Test
    void failedExecutionIsNotConfirmed() {
        allowExecution("FAILED");
        var audit = service.inspectSkippedExecution(JOB, WINDOW, SEASON_ID);
        assertEquals(QuestAssignmentIntegrityService.AuditOutcome.NOT_SUCCEEDED, audit.outcome());
        verifyNoInteractions(periods, assignments);
    }

    /**
     * SUCCEEDED 실행권에 기간이 없으면 불일치로 분류합니다.
     * 누락된 기간을 자동으로 다시 만들지 않습니다.
     * 로그에서 식별할 수 있는 PERIOD_MISSING 코드를 전달합니다.
     */
    @Test
    void succeededExecutionWithMissingPeriodIsCorrupt() {
        allowExecution("SUCCEEDED");
        var audit = service.inspectSkippedExecution(JOB, WINDOW, SEASON_ID);
        assertEquals(QuestAssignmentIntegrityService.AuditOutcome.INCONSISTENT, audit.outcome());
        assertTrue(audit.detail().contains("PERIOD_MISSING"));
        verify(periods, never()).save(any());
    }

    /**
     * FAILED 상태의 기간은 실제 배정 성공이 아닙니다.
     * SUCCEEDED 실행권이 있더라도 운영 오류로 노출합니다.
     * 원래 실패 상태를 자동으로 ACTIVE로 바꾸지 않습니다.
     */
    @Test
    void succeededExecutionWithFailedPeriodIsCorrupt() {
        allowExecution("SUCCEEDED");
        QuestPeriod p = mock(QuestPeriod.class);
        when(p.getStatus()).thenReturn("FAILED");
        when(periods.findByPeriodTypeAndStartAt("DAILY", START)).thenReturn(Optional.of(p));
        var audit = service.inspectSkippedExecution(JOB, WINDOW, SEASON_ID);
        assertEquals(QuestAssignmentIntegrityService.AuditOutcome.INCONSISTENT, audit.outcome());
        assertTrue(audit.detail().contains("PERIOD_NOT_ACTIVE"));
    }

    /**
     * ACTIVE 기간에 슬롯이 하나밖에 없으면 불완전한 데이터입니다.
     * 검증 대상은 스냅샷 3개가 정상적으로 저장됐는지입니다.
     * 슬롯을 다시 만들어 보상을 중복할 가능성을 제거합니다.
     */
    @Test
    void succeededExecutionWithMissingAssignmentIsCorrupt() {
        allowExecution("SUCCEEDED");
        allowActivePeriod(101L, SEASON_ID, WINDOW);
        when(assignments.findAllWithSnapshotByPeriodId(101L)).thenReturn(List.of(mock(QuestAssignment.class)));
        var audit = service.inspectSkippedExecution(JOB, WINDOW, SEASON_ID);
        assertEquals(QuestAssignmentIntegrityService.AuditOutcome.INCONSISTENT, audit.outcome());
        assertTrue(audit.detail().contains("ASSIGNMENT_COUNT_MISMATCH"));
    }

    /**
     * 일일 3개와 LOW·MEDIUM·HIGH의 올바른 배치를 구성합니다.
     * 버전이 실제 원본 템플릿에 속하는지도 함께 검증합니다.
     * 완료된 이력이 정상이라면 오탐 없이 CONSISTENT로 반환합니다.
     */
    @Test
    void succeededExecutionWithCompleteSnapshotIsConsistent() {
        allowExecution("SUCCEEDED");
        allowActivePeriod(101L, SEASON_ID, WINDOW);
        List<QuestAssignment> valid = validAssignments(101L, "DAILY");
        when(assignments.findAllWithSnapshotByPeriodId(101L)).thenReturn(valid);
        var audit = service.inspectSkippedExecution(JOB, WINDOW, SEASON_ID);
        assertEquals(QuestAssignmentIntegrityService.AuditOutcome.CONSISTENT, audit.outcome());
        assertNull(audit.detail());
        verify(assignments, never()).save(any());
    }

    /**
     * 슬롯 번호가 중간에 잘못되면 COUNT가 맞아도 부정확한 배정입니다.
     * 중복·순서가 어긋난 스냅샷은 성공으로 처리하지 않습니다.
     * 검증 결과에 INVALID_SLOT 식별자가 포함돼야 합니다.
     */
    @Test
    void nonConsecutiveSlotIsCorrupt() {
        allowExecution("SUCCEEDED");
        allowActivePeriod(101L, SEASON_ID, WINDOW);
        List<QuestAssignment> valid = validAssignments(101L, "DAILY");
        when(valid.get(1).getSlotNumber()).thenReturn(4);
        when(assignments.findAllWithSnapshotByPeriodId(101L)).thenReturn(valid);
        var audit = service.inspectSkippedExecution(JOB, WINDOW, SEASON_ID);
        assertTrue(audit.detail().contains("INVALID_SLOT"));
    }

    /**
     * 날짜가 같아도 해당 기간의 시즌 FK가 다르면 승인하지 않습니다.
     * 현재 활성 시즌과 연결돼 있어야 합니다.
     * 시즌 ID 비교 실패를 분리된 진단 코드로 반환합니다.
     */
    @Test
    void wrongSeasonIsCorrupt() {
        allowExecution("SUCCEEDED");
        allowActivePeriod(101L, 999L, WINDOW);
        var audit = service.inspectSkippedExecution(JOB, WINDOW, SEASON_ID);
        assertTrue(audit.detail().contains("SEASON_MISMATCH"));
    }

    /**
     * 신규 배정은 Scheduler의 SUCCEEDED 기록 전에 Snapshot을 검사합니다.
     * 일일 정상 데이터는 검증에 통과해야 합니다.
     * 이 단계에서는 SchedulerExecutionRepository를 조회하지 않습니다.
     */
    @Test
    void committedAssignmentCanBeCheckedBeforeExecutionSucceeds() {
        allowActivePeriod(101L, SEASON_ID, WINDOW);
        List<QuestAssignment> valid = validAssignments(101L, "DAILY");
        when(assignments.findAllWithSnapshotByPeriodId(101L)).thenReturn(valid);
        var validation = service.verifyCommittedAssignment(WINDOW, SEASON_ID);
        assertTrue(validation.valid());
        verifyNoInteractions(executions);
    }

    /**
     * 일일 난이도 순서가 LOW·HIGH·HIGH이면 3개여도 잘못된 배정입니다.
     * 이전 검증은 null 여부만 확인해 이 문제를 감지하지 못했습니다.
     * 변경된 로직은 DIFFICULTY_SLOT_MISMATCH로 처리해야 합니다.
     */
    @Test
    void dailyIncorrectDifficultyDistributionIsCorrupt() {
        allowExecution("SUCCEEDED");
        allowActivePeriod(101L, SEASON_ID, WINDOW);
        List<QuestAssignment> valid = validAssignments(101L, "DAILY");
        when(valid.get(1).getDifficultySnapshot()).thenReturn("HIGH");
        when(assignments.findAllWithSnapshotByPeriodId(101L)).thenReturn(valid);
        var audit = service.inspectSkippedExecution(JOB, WINDOW, SEASON_ID);
        assertEquals(QuestAssignmentIntegrityService.AuditOutcome.INCONSISTENT, audit.outcome());
        assertTrue(audit.detail().contains("DIFFICULTY_SLOT_MISMATCH"));
    }

    /**
     * 템플릿 A의 배정에 다른 템플릿 B의 버전이 연결된 상황입니다.
     * 각각의 FK가 존재하더라도 서로 다른 템플릿이면 비정상입니다.
     * TEMPLATE_VERSION_MISMATCH가 발생하고 DB를 변경하지 않습니다.
     */
    @Test
    void versionOwnedByAnotherTemplateIsCorrupt() {
        allowExecution("SUCCEEDED");
        allowActivePeriod(101L, SEASON_ID, WINDOW);
        List<QuestAssignment> valid = validAssignments(101L, "DAILY");
        QuestTemplateVersion firstVersion = valid.get(0).getQuestTemplateVersion();
        QuestTemplate other = mock(QuestTemplate.class);
        when(other.getId()).thenReturn(999L);
        when(firstVersion.getQuestTemplate()).thenReturn(other);
        when(assignments.findAllWithSnapshotByPeriodId(101L)).thenReturn(valid);
        var audit = service.inspectSkippedExecution(JOB, WINDOW, SEASON_ID);
        assertEquals(QuestAssignmentIntegrityService.AuditOutcome.INCONSISTENT, audit.outcome());
        assertTrue(audit.detail().contains("TEMPLATE_VERSION_MISMATCH"));
    }

    /**
     * 주간 7개는 LOW 3개·MEDIUM 2개·HIGH 2개 순서여야 합니다.
     * 템플릿 버전도 모두 자기 템플릿에 연결된 상태입니다.
     * 정상 주간 배정이 새 난이도 검사에서 오탐하지 않는지 확인합니다.
     */
    @Test
    void weeklyThreeTwoTwoDistributionIsConsistent() {
        allowActivePeriod(101L, SEASON_ID, WEEKLY_WINDOW);
        List<QuestAssignment> valid = validAssignments(101L, "WEEKLY");
        when(assignments.findAllWithSnapshotByPeriodId(101L)).thenReturn(valid);
        var result = service.verifyCommittedAssignment(WEEKLY_WINDOW, SEASON_ID);
        assertTrue(result.valid());
    }

    /**
     * 주간 슬롯 4는 MEDIUM이어야 하며 HIGH가 들어갈 수 없습니다.
     * 7개 슬롯의 수량이 정확해도 난이도별 3/2/2 규칙을 검증합니다.
     * 오류 시 어느 슬롯인지 반환된 상세에서 확인할 수 있습니다.
     */
    @Test
    void weeklyIncorrectDifficultyDistributionIsCorrupt() {
        allowActivePeriod(101L, SEASON_ID, WEEKLY_WINDOW);
        List<QuestAssignment> valid = validAssignments(101L, "WEEKLY");
        when(valid.get(3).getDifficultySnapshot()).thenReturn("HIGH");
        when(assignments.findAllWithSnapshotByPeriodId(101L)).thenReturn(valid);
        var result = service.verifyCommittedAssignment(WEEKLY_WINDOW, SEASON_ID);
        assertFalse(result.valid());
        assertTrue(result.reason().contains("DIFFICULTY_SLOT_MISMATCH"));
    }

    /**
     * 실행 결과가 주어진 상태의 Scheduler 이력을 Mock으로 제공합니다.
     * 상태 조회는 Scheduler 실행권을 선점하는 동작이 아닙니다.
     * 테스트마다 독립 Mock이므로 외부 DB를 사용하지 않습니다.
     */
    private void allowExecution(String status) {
        SchedulerExecution execution = mock(SchedulerExecution.class);
        when(execution.getStatus()).thenReturn(status);
        when(executions.findByJobTypeAndScheduledFor(JOB, START)).thenReturn(Optional.of(execution));
    }

    /**
     * 주어진 논리 기간과 시즌에 속하는 ACTIVE 기간 Mock을 만듭니다.
     * 각 테스트는 직접 선택한 기간 경계를 사용해 검증합니다.
     * ID·일자·시작·끝 시각이 실제 조회 결과와 동일하게 설정됩니다.
     */
    private void allowActivePeriod(long id, long seasonId, QuestPeriodCalendar.PeriodWindow window) {
        QuestPeriod period = mock(QuestPeriod.class);
        Season season = mock(Season.class);
        when(period.getStatus()).thenReturn("ACTIVE");
        when(period.getSeason()).thenReturn(season);
        when(season.getId()).thenReturn(seasonId);
        when(period.getId()).thenReturn(id);
        when(period.getPeriodDate()).thenReturn(window.periodDate());
        when(period.getStartAt()).thenReturn(window.startAt());
        when(period.getEndAt()).thenReturn(window.endAt());
        when(periods.findByPeriodTypeAndStartAt(window.periodType(), window.startAt()))
                .thenReturn(Optional.of(period));
    }

    /**
     * 실제 슬롯 수와 확정된 난이도 순서를 갖는 배정 Mock 목록을 만듭니다.
     * 각 버전의 원본 템플릿 FK가 해당 배정 템플릿과 같도록 구성합니다.
     * 반환할 목록은 외부 Repository stubbing이 시작되기 전에 완성합니다.
     */
    private List<QuestAssignment> validAssignments(long periodId, String periodType) {
        int count = "DAILY".equals(periodType) ? 3 : 7;
        List<QuestAssignment> result = new ArrayList<>();
        for (int slot = 1; slot <= count; slot++) {
            QuestAssignment assignment = mock(QuestAssignment.class);
            QuestPeriod period = mock(QuestPeriod.class);
            QuestTemplate template = mock(QuestTemplate.class);
            QuestTemplateVersion version = mock(QuestTemplateVersion.class);
            when(assignment.getSlotNumber()).thenReturn(slot);
            when(assignment.getQuestPeriod()).thenReturn(period);
            when(period.getId()).thenReturn(periodId);
            when(assignment.getQuestTemplate()).thenReturn(template);
            when(template.getId()).thenReturn((long) slot);
            when(assignment.getQuestTemplateVersion()).thenReturn(version);
            when(version.getId()).thenReturn((long) slot + 10);
            when(version.getQuestTemplate()).thenReturn(template);
            when(assignment.getTitleSnapshot()).thenReturn("title " + slot);
            when(assignment.getDescriptionSnapshot()).thenReturn("description");
            when(assignment.getDifficultySnapshot()).thenReturn(difficulty(periodType, slot));
            when(assignment.getVerificationTypeSnapshot()).thenReturn("SELF_CHECK");
            when(assignment.getVerificationParametersSnapshot()).thenReturn("{}");
            when(assignment.getCreatureExperienceSnapshot()).thenReturn(100L);
            when(assignment.getContributionScoreSnapshot()).thenReturn(10L);
            result.add(assignment);
        }
        return result;
    }

    /**
     * 테스트용 올바른 난이도 Snapshot 값을 제공합니다.
     * 일일 1/1/1과 주간 3/2/2 규칙을 하드코딩한 비교용 Fixture입니다.
     * 이 값을 변경하는 테스트는 무결성 검사에서 오류가 발생해야 합니다.
     */
    private String difficulty(String periodType, int slotNumber) {
        if ("DAILY".equals(periodType)) {
            return slotNumber == 1 ? "LOW" : slotNumber == 2 ? "MEDIUM" : "HIGH";
        }
        return slotNumber <= 3 ? "LOW" : slotNumber <= 5 ? "MEDIUM" : "HIGH";
    }
}
