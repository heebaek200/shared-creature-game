package com.team.sharedcreature.quest.application;

import com.team.sharedcreature.admin.domain.SchedulerExecution;
import com.team.sharedcreature.admin.infrastructure.SchedulerExecutionRepository;
import com.team.sharedcreature.quest.domain.QuestAssignment;
import com.team.sharedcreature.quest.domain.QuestPeriod;
import com.team.sharedcreature.quest.infrastructure.QuestAssignmentRepository;
import com.team.sharedcreature.quest.infrastructure.QuestPeriodRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;

/**
 * Scheduler 실행 이력과 퀘스트 기간·Snapshot의 실제 DB 정합성을 검사합니다.
 * 실행 이력이 SUCCEEDED인 경우에만 누락을 확정적인 불일치로 분류합니다.
 * 검사 자체는 읽기 전용이며 과거 배정·완료·보상을 수정하지 않습니다.
 */
@Service
public class QuestAssignmentIntegrityService {

    private final SchedulerExecutionRepository executionRepository;
    private final QuestPeriodRepository periodRepository;
    private final QuestAssignmentRepository assignmentRepository;

    /**
     * Scheduler 이력, 기간, 배정 Snapshot을 조회할 Repository를 주입합니다.
     * JPA 관리 엔티티의 식별자와 실제 배정 개수를 함께 검증합니다.
     * 모든 조회는 별도의 읽기 전용 트랜잭션에서 수행됩니다.
     */
    public QuestAssignmentIntegrityService(
            SchedulerExecutionRepository executionRepository,
            QuestPeriodRepository periodRepository,
            QuestAssignmentRepository assignmentRepository
    ) {
        this.executionRepository = executionRepository;
        this.periodRepository = periodRepository;
        this.assignmentRepository = assignmentRepository;
    }

    /**
     * Runner의 업무 콜백이 완료된 직후, 성공 이력을 기록하기 전에 정합성을 검사합니다.
     * 배정 트랜잭션은 이미 Commit된 상태여야 하며 여기서는 DB를 수정하지 않습니다.
     * 실패 시 호출자가 예외를 발생시켜 Scheduler를 SUCCEEDED로 기록하지 못하게 합니다.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = true)
    public Validation verifyCommittedAssignment(QuestPeriodCalendar.PeriodWindow window, Long seasonId) {
        Objects.requireNonNull(window, "window");
        Objects.requireNonNull(seasonId, "seasonId");

        // 기간과 Snapshot은 한 읽기 트랜잭션에서 검증해 중간 상태 판정을 피합니다.
        return validatePeriod(window, seasonId);
    }

    /**
     * SKIPPED된 Scheduler 실행의 실제 성공 여부와 배정 데이터를 비교합니다.
     * RUNNING·FAILED 또는 이력 없음은 SUCCEEDED가 아니므로 불일치로 단정하지 않습니다.
     * SUCCEEDED인데 기간이나 Snapshot이 잘못된 경우에는 수동 조치가 필요한 결과를 반환합니다.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = true)
    public ExecutionAudit inspectSkippedExecution(
            String jobType,
            QuestPeriodCalendar.PeriodWindow window,
            Long seasonId
    ) {
        Objects.requireNonNull(jobType, "jobType");
        Objects.requireNonNull(window, "window");
        Objects.requireNonNull(seasonId, "seasonId");

        // SKIPPED는 다른 실행자가 실행 중인 경우와 이미 성공한 경우를 함께 뜻합니다.
        Optional<SchedulerExecution> execution = executionRepository.findByJobTypeAndScheduledFor(
                jobType, window.startAt());
        if (execution.isEmpty() || !"SUCCEEDED".equals(execution.orElseThrow().getStatus())) {
            return new ExecutionAudit(AuditOutcome.NOT_SUCCEEDED, null);
        }

        // 이력 SUCCEEDED가 확정된 경우에만 실제 저장 상태를 완전하게 검증합니다.
        Validation validation = validatePeriod(window, seasonId);
        return validation.valid()
                ? new ExecutionAudit(AuditOutcome.CONSISTENT, null)
                : new ExecutionAudit(AuditOutcome.INCONSISTENT, validation.reason());
    }

    /**
     * 기간의 상태·달력·시즌 ID와 Snapshot의 모든 슬롯을 검증합니다.
     * 단순 COUNT 외에 연속 슬롯과 템플릿 중복까지 확인합니다.
     * 외부에서 데이터가 훼손되어도 자동 재배정으로 원본 Snapshot을 덮어쓰지 않습니다.
     */
    private Validation validatePeriod(QuestPeriodCalendar.PeriodWindow window, Long seasonId) {
        QuestPeriod period = periodRepository.findByPeriodTypeAndStartAt(
                window.periodType(), window.startAt()).orElse(null);
        if (period == null) {
            return invalid("PERIOD_MISSING", window);
        }
        if (!"ACTIVE".equals(period.getStatus())) {
            return invalid("PERIOD_NOT_ACTIVE:" + period.getStatus(), window);
        }
        if (period.getSeason() == null || !seasonId.equals(period.getSeason().getId())) {
            return invalid("SEASON_MISMATCH", window);
        }

        // 논리 기간과 DB 기간의 날짜·끝 시각까지 일치해야 합니다.
        if (!Objects.equals(window.periodDate(), period.getPeriodDate())
                || !Objects.equals(window.endAt(), period.getEndAt())
                || !Objects.equals(window.startAt(), period.getStartAt())) {
            return invalid("WINDOW_MISMATCH", window);
        }

        int expected = switch (window.periodType()) {
            case "DAILY" -> 3;
            case "WEEKLY" -> 7;
            default -> throw new IllegalArgumentException("지원하지 않는 기간: " + window.periodType());
        };
        List<QuestAssignment> assignments = assignmentRepository.findAllWithSnapshotByPeriodId(period.getId());
        if (assignments.size() != expected) {
            return invalid("ASSIGNMENT_COUNT_MISMATCH:actual=" + assignments.size()
                    + ",expected=" + expected, window);
        }

        // 정렬된 1..N 슬롯과 유효한 FK·Snapshot을 검사합니다.
        Set<Long> templateIds = new HashSet<>();
        for (int index = 0; index < assignments.size(); index++) {
            QuestAssignment assignment = assignments.get(index);
            if (assignment.getSlotNumber() == null || assignment.getSlotNumber() != index + 1
                    || assignment.getQuestPeriod() == null
                    || !Objects.equals(assignment.getQuestPeriod().getId(), period.getId())) {
                return invalid("INVALID_SLOT:" + (index + 1), window);
            }
            if (assignment.getQuestTemplate() == null
                    || assignment.getQuestTemplate().getId() == null
                    || !templateIds.add(assignment.getQuestTemplate().getId())
                    || assignment.getQuestTemplateVersion() == null
                    || assignment.getQuestTemplateVersion().getId() == null) {
                return invalid("INVALID_TEMPLATE_REFERENCE", window);
            }
            if (assignment.getTitleSnapshot() == null
                    || assignment.getDescriptionSnapshot() == null
                    || assignment.getDifficultySnapshot() == null
                    || assignment.getVerificationTypeSnapshot() == null
                    || assignment.getVerificationParametersSnapshot() == null
                    || assignment.getCreatureExperienceSnapshot() == null
                    || assignment.getContributionScoreSnapshot() == null) {
                return invalid("MISSING_SNAPSHOT", window);
            }
        }
        return new Validation(true, null);
    }

    /**
     * 구조화된 정합성 실패 사유를 생성합니다.
     * 타입·논리 시작 시각을 포함하여 로그에서 어떤 실행을 점검해야 하는지 알립니다.
     * 민감한 사용자 정보나 DB 연결 정보는 메시지에 포함하지 않습니다.
     */
    private Validation invalid(String code, QuestPeriodCalendar.PeriodWindow window) {
        return new Validation(false, "[QUEST_ASSIGNMENT_INTEGRITY] code=" + code
                + ",periodType=" + window.periodType() + ",startAt=" + window.startAt());
    }

    /**
     * 검증 성공 여부와 실패 시 진단 문자열을 표현합니다.
     * 성공이면 reason은 null이며 수정할 데이터가 없습니다.
     * 실패이면 호출자가 Scheduler 성공 확정을 중단해야 합니다.
     */
    public record Validation(boolean valid, String reason) {
    }

    /**
     * SKIPPED 이후 검사 결과를 명시적으로 구분합니다.
     * NOT_SUCCEEDED는 RUNNING·FAILED·이력 없음이 포함된 미확정 상태입니다.
     * INCONSISTENT는 이력 SUCCEEDED와 실제 데이터가 모순되는 상태입니다.
     */
    public enum AuditOutcome {
        CONSISTENT,
        NOT_SUCCEEDED,
        INCONSISTENT
    }

    /**
     * SKIPPED 이력의 판정과 오류 설명을 반환합니다.
     * 정상적 미확정 실행에는 오류를 생성하지 않습니다.
     * 실제 불일치가 있을 때만 진단 문자열을 제공합니다.
     */
    public record ExecutionAudit(AuditOutcome outcome, String detail) {
    }
}
