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
 * R-3 Scheduler SUCCEEDED 이력과 실제 퀘스트 배정의 정합성 검사를 검증합니다.
 * RUNNING은 성공 이력이 아니므로 누락을 불일치로 단정하지 않습니다.
 * 읽기 검사기에 저장 호출이 없는지도 확인합니다.
 */
class QuestAssignmentIntegrityServiceTest {

    private static final Instant START = Instant.parse("2026-11-09T15:00:00Z");
    private static final QuestPeriodCalendar.PeriodWindow WINDOW =
            new QuestPeriodCalendar.PeriodWindow("DAILY", LocalDate.of(2026, 11, 10),
                    START, START.plusSeconds(86400), 8);
    private static final Long SEASON_ID = 7L;
    private static final String JOB = "QUEST_ASSIGN_DAILY";

    private final SchedulerExecutionRepository executions = mock(SchedulerExecutionRepository.class);
    private final QuestPeriodRepository periods = mock(QuestPeriodRepository.class);
    private final QuestAssignmentRepository assignments = mock(QuestAssignmentRepository.class);
    private final QuestAssignmentIntegrityService service =
            new QuestAssignmentIntegrityService(executions, periods, assignments);

    /**
     * RUNNING을 SUCCEEDED와 구별하고 기간 누락을 성급히 오류로 보고하지 않습니다.
     * 다른 실행자가 기간과 Snapshot을 현재 생성 중일 수 있습니다.
     * 불확정 이력에서는 실제 배정 데이터를 조회하지 않습니다.
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
     * FAILED는 다음 실행이 재선점할 상태로 판단합니다.
     * 과거 실패에 의한 미배정은 정상적인 복구 대상으로 남아 있습니다.
     * 실행 상태를 변경하지 않고 감사를 종료합니다.
     */
    @Test
    void failedExecutionIsNotConfirmed() {
        allowExecution("FAILED");
        var audit = service.inspectSkippedExecution(JOB, WINDOW, SEASON_ID);
        assertEquals(QuestAssignmentIntegrityService.AuditOutcome.NOT_SUCCEEDED, audit.outcome());
        verifyNoInteractions(periods, assignments);
    }

    /**
     * SUCCESS인데 기간 행이 없다면 정합성 위반을 탐지합니다.
     * 실제로 존재하지 않는 기간을 성공 처리할 수 없습니다.
     * 누락된 기간을 검사기가 INSERT하지 않습니다.
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
     * 이력이 SUCCEEDED여도 기간이 FAILED라면 업무는 완료되지 않았습니다.
     * 실행 이력과 업무 상태의 모순을 오류로 보고해야 합니다.
     * FAILED 기간을 ACTIVE로 전환하지 않습니다.
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
     * ACTIVE 기간에 슬롯이 2개뿐이면 배정 성공으로 간주할 수 없습니다.
     * 단순 ACTIVE 상태가 아닌 실제 Snapshot 수로 검증합니다.
     * 원본을 다시 선정하거나 자동으로 수정하지 않습니다.
     */
    @Test
    void succeededExecutionWithMissingAssignmentIsCorrupt() {
        allowExecution("SUCCEEDED");
        allowActivePeriod(101L, SEASON_ID);
        when(assignments.findAllWithSnapshotByPeriodId(101L)).thenReturn(List.of(mock(QuestAssignment.class)));
        var audit = service.inspectSkippedExecution(JOB, WINDOW, SEASON_ID);
        assertEquals(QuestAssignmentIntegrityService.AuditOutcome.INCONSISTENT, audit.outcome());
        assertTrue(audit.detail().contains("ASSIGNMENT_COUNT_MISMATCH"));
    }

    /**
     * 현재 ACTIVE 시즌·기간에 슬롯 1,2,3이 모두 있으면 정상입니다.
     * 결과는 CONSISTENT로 보고하고 DB에는 쓰기를 하지 않습니다.
     * 정렬된 Snapshot의 FK와 필수 값도 검사됩니다.
     */
    @Test
    void succeededExecutionWithCompleteSnapshotIsConsistent() {
        allowExecution("SUCCEEDED");
        allowActivePeriod(101L, SEASON_ID);
        // 정상 배정 Mock을 먼저 완전히 구성합니다.
        // 다른 Mock의 stubbing 도중 새로운 stubbing을 시작하지 않습니다.
        List<QuestAssignment> valid = validAssignments(101L);

        // 완성된 목록을 반환값으로 등록합니다.
        when(assignments.findAllWithSnapshotByPeriodId(101L))
                .thenReturn(valid);
        var audit = service.inspectSkippedExecution(JOB, WINDOW, SEASON_ID);
        assertEquals(QuestAssignmentIntegrityService.AuditOutcome.CONSISTENT, audit.outcome());
        assertNull(audit.detail());
        verify(assignments, never()).save(any());
    }

    /**
     * 슬롯 수가 같더라도 연속 번호가 아니면 잘못된 배정입니다.
     * 기존 UNIQUE 제약을 우회한 외부 DB 변경도 진단할 수 있습니다.
     * 검증은 기간 상태를 변경하지 않습니다.
     */
    @Test
    void nonConsecutiveSlotIsCorrupt() {
        allowExecution("SUCCEEDED");
        allowActivePeriod(101L, SEASON_ID);
        List<QuestAssignment> valid = validAssignments(101L);
        when(valid.get(1).getSlotNumber()).thenReturn(4);
        when(assignments.findAllWithSnapshotByPeriodId(101L)).thenReturn(valid);
        var audit = service.inspectSkippedExecution(JOB, WINDOW, SEASON_ID);
        assertTrue(audit.detail().contains("INVALID_SLOT"));
    }

    /**
     * 기간이 다른 시즌을 참조하면 같은 일자의 배정으로 승인할 수 없습니다.
     * 시즌 재생성 경계에서 잘못된 FK를 발견하기 위한 검증입니다.
     * 기간 날짜가 맞아도 시즌 ID를 반드시 비교합니다.
     */
    @Test
    void wrongSeasonIsCorrupt() {
        allowExecution("SUCCEEDED");
        allowActivePeriod(101L, 999L);
        var audit = service.inspectSkippedExecution(JOB, WINDOW, SEASON_ID);
        assertTrue(audit.detail().contains("SEASON_MISMATCH"));
    }

    /**
     * 신규 선점된 실행은 아직 SUCCEEDED가 아니어도 배정 Commit을 검사합니다.
     * Runner의 성공 기록 전에 별도 읽기 트랜잭션으로 검사하기 위함입니다.
     * 현재 Scheduler 상태는 조회할 필요가 없습니다.
     */
    @Test
    void committedAssignmentCanBeCheckedBeforeExecutionSucceeds() {
        allowActivePeriod(101L, SEASON_ID);
        // 정상 배정 Mock을 먼저 완전히 구성합니다.
        // 다른 Mock의 stubbing 도중 새로운 stubbing을 시작하지 않습니다.
        List<QuestAssignment> valid = validAssignments(101L);

        // 완성된 목록을 반환값으로 등록합니다.
        when(assignments.findAllWithSnapshotByPeriodId(101L))
                .thenReturn(valid);
        var validation = service.verifyCommittedAssignment(WINDOW, SEASON_ID);
        assertTrue(validation.valid());
        verifyNoInteractions(executions);
    }

    /**
     * 지정한 스케줄러 작업에 상태가 있는 실행 이력을 제공합니다.
     * 실제 DB UNIQUE를 모사하는 대신 단일 조회 결과로 대체합니다.
     * 런타임 상태에 따른 감사 분기만 검증합니다.
     */
    private void allowExecution(String status) {
        SchedulerExecution execution = mock(SchedulerExecution.class);
        when(execution.getStatus()).thenReturn(status);
        when(executions.findByJobTypeAndScheduledFor(JOB, START)).thenReturn(Optional.of(execution));
    }

    /**
     * 동일한 기간 경계와 시즌을 가지는 ACTIVE 기간을 반환합니다.
     * 특정 검사 이전에 실패하는 경우 불필요한 Stub을 허용합니다.
     * 테스트 대상은 개별 정합성 분기입니다.
     */
    private void allowActivePeriod(long id, long seasonId) {
        QuestPeriod period = mock(QuestPeriod.class);
        Season season = mock(Season.class);
        when(period.getStatus()).thenReturn("ACTIVE");
        when(period.getSeason()).thenReturn(season);
        when(season.getId()).thenReturn(seasonId);
        when(period.getId()).thenReturn(id);
        when(period.getPeriodDate()).thenReturn(WINDOW.periodDate());
        when(period.getStartAt()).thenReturn(WINDOW.startAt());
        when(period.getEndAt()).thenReturn(WINDOW.endAt());
        when(periods.findByPeriodTypeAndStartAt("DAILY", START)).thenReturn(Optional.of(period));
    }

    /**
     * 1부터 순서대로 연결된 정상 퀘스트 배정 3건을 생성합니다.
     * 모든 FK와 스냅샷 필수 필드를 Mock에 설정합니다.
     * 누락·슬롯 변경 등의 테스트에서 반환 목록을 재사용합니다.
     */
    private List<QuestAssignment> validAssignments(long periodId) {
        List<QuestAssignment> result = new ArrayList<>();
        for (int slot = 1; slot <= 3; slot++) {
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
            when(assignment.getTitleSnapshot()).thenReturn("title " + slot);
            when(assignment.getDescriptionSnapshot()).thenReturn("description");
            when(assignment.getDifficultySnapshot()).thenReturn("LOW");
            when(assignment.getVerificationTypeSnapshot()).thenReturn("SELF_CHECK");
            when(assignment.getVerificationParametersSnapshot()).thenReturn("{}");
            when(assignment.getCreatureExperienceSnapshot()).thenReturn(100L);
            when(assignment.getContributionScoreSnapshot()).thenReturn(10L);
            result.add(assignment);
        }
        return result;
    }
}
