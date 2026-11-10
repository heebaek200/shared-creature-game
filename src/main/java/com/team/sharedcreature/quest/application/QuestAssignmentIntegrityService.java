package com.team.sharedcreature.quest.application;

import com.team.sharedcreature.admin.domain.SchedulerExecution;
import com.team.sharedcreature.admin.infrastructure.SchedulerExecutionRepository;
import com.team.sharedcreature.quest.domain.QuestAssignment;
import com.team.sharedcreature.quest.domain.QuestPeriod;
import com.team.sharedcreature.quest.domain.QuestTemplate;
import com.team.sharedcreature.quest.domain.QuestTemplateVersion;
import com.team.sharedcreature.quest.infrastructure.QuestAssignmentRepository;
import com.team.sharedcreature.quest.infrastructure.QuestPeriodRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Scheduler 성공 이력과 퀘스트 기간·Snapshot의 실제 DB 정합성을 검증합니다.
 * 기간 상태·슬롯 순서·난이도 구성·템플릿 버전 FK 소속 관계를 함께 확인합니다.
 * 데이터 오류를 발견하더라도 과거 배정이나 보상 기록을 자동으로 변경하지 않습니다.
 */
@Service
public class QuestAssignmentIntegrityService {

    private final SchedulerExecutionRepository executionRepository;
    private final QuestPeriodRepository periodRepository;
    private final QuestAssignmentRepository assignmentRepository;

    /**
     * 실행 이력, 기간, Snapshot을 조회할 Repository를 주입합니다.
     * Snapshot은 배정 시점 불변 데이터를 기준으로 검사합니다.
     * 모든 검사는 별도의 읽기 전용 트랜잭션으로 수행합니다.
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
     * Scheduler 실행 성공을 기록하기 전에 Commit된 배정을 검증합니다.
     * 실제 기간이 ACTIVE인지, 슬롯과 연결관계가 정확한지 확인합니다.
     * 검증 자체는 읽기 전용이며 실패 상세를 호출자에게 반환합니다.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = true)
    public Validation verifyCommittedAssignment(QuestPeriodCalendar.PeriodWindow window, Long seasonId) {
        Objects.requireNonNull(window, "window");
        Objects.requireNonNull(seasonId, "seasonId");

        // 실행권의 성공 확정 전에 이미 커밋된 업무 데이터를 재조회합니다.
        return validatePeriod(window, seasonId);
    }

    /**
     * SKIPPED로 반환된 실행권이 정말 완료된 데이터인지 검사합니다.
     * RUNNING·FAILED·이력 없음은 완료로 단정하지 않아 다음 주기에 재확인합니다.
     * SUCCEEDED인데 실제 기간이 손상됐다면 운영자가 볼 수 있는 오류를 반환합니다.
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

        // RUNNING에 의한 정상 경합은 성공 데이터 누락으로 오판하지 않습니다.
        Optional<SchedulerExecution> execution = executionRepository.findByJobTypeAndScheduledFor(
                jobType, window.startAt());
        if (execution.isEmpty() || !"SUCCEEDED".equals(execution.orElseThrow().getStatus())) {
            return new ExecutionAudit(AuditOutcome.NOT_SUCCEEDED, null);
        }

        // 이미 SUCCEEDED인 작업만 실제 Snapshot과 비교합니다.
        Validation validation = validatePeriod(window, seasonId);
        return validation.valid()
                ? new ExecutionAudit(AuditOutcome.CONSISTENT, null)
                : new ExecutionAudit(AuditOutcome.INCONSISTENT, validation.reason());
    }

    /**
     * 퀘스트 기간과 배정 슬롯의 업무 무결성을 검증합니다.
     * 단순 개수가 아닌 난이도 순서와 버전 소속까지 확인합니다.
     * 배정 당시 Snapshot은 이후 템플릿 관리 변경과 구분하여 검사합니다.
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

        // 동일 시즌이라도 논리 시작·종료 시각과 기간 일자가 동일해야 합니다.
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

        // 슬롯은 Repository의 ORDER BY slotNumber 결과를 1..N 순서로 검사합니다.
        Set<Long> templateIds = new HashSet<>();
        for (int index = 0; index < assignments.size(); index++) {
            QuestAssignment assignment = assignments.get(index);
            int slotNumber = index + 1;
            if (assignment.getSlotNumber() == null || assignment.getSlotNumber() != slotNumber
                    || assignment.getQuestPeriod() == null
                    || !Objects.equals(assignment.getQuestPeriod().getId(), period.getId())) {
                return invalid("INVALID_SLOT:" + slotNumber, window);
            }

            QuestTemplate template = assignment.getQuestTemplate();
            QuestTemplateVersion version = assignment.getQuestTemplateVersion();
            if (template == null || template.getId() == null
                    || !templateIds.add(template.getId())
                    || version == null || version.getId() == null) {
                return invalid("INVALID_TEMPLATE_REFERENCE", window);
            }

            // 버전 FK의 존재뿐 아니라 해당 버전이 실제 템플릿에 속하는지도 검사합니다.
            if (version.getQuestTemplate() == null
                    || !Objects.equals(version.getQuestTemplate().getId(), template.getId())) {
                return invalid("TEMPLATE_VERSION_MISMATCH:slot=" + slotNumber, window);
            }

            // Snapshot의 난이도는 일일 1/1/1, 주간 3/2/2의 고정 슬롯 순서와 비교합니다.
            String difficulty = expectedDifficulty(window.periodType(), slotNumber);
            if (!difficulty.equals(assignment.getDifficultySnapshot())) {
                return invalid("DIFFICULTY_SLOT_MISMATCH:slot=" + slotNumber
                        + ",expected=" + difficulty
                        + ",actual=" + assignment.getDifficultySnapshot(), window);
            }
            if (assignment.getTitleSnapshot() == null
                    || assignment.getDescriptionSnapshot() == null
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
     * 확정된 배정 규칙에 따른 각 슬롯의 난이도를 반환합니다.
     * DAILY는 1 LOW / 1 MEDIUM / 1 HIGH, WEEKLY는 3 LOW / 2 MEDIUM / 2 HIGH입니다.
     * 슬롯 번호가 범위를 벗어나면 예상하지 못한 검사 호출이므로 예외를 반환합니다.
     */
    private String expectedDifficulty(String periodType, int slotNumber) {
        if ("DAILY".equals(periodType)) {
            return switch (slotNumber) {
                case 1 -> "LOW";
                case 2 -> "MEDIUM";
                case 3 -> "HIGH";
                default -> throw new IllegalArgumentException("DAILY 슬롯 범위 초과: " + slotNumber);
            };
        }
        if ("WEEKLY".equals(periodType)) {
            if (slotNumber >= 1 && slotNumber <= 3) return "LOW";
            if (slotNumber >= 4 && slotNumber <= 5) return "MEDIUM";
            if (slotNumber >= 6 && slotNumber <= 7) return "HIGH";
        }
        throw new IllegalArgumentException("지원하지 않는 기간 또는 슬롯: " + periodType + "/" + slotNumber);
    }

    /**
     * 구조화된 진단 메시지를 만들어 운영 로그에 남깁니다.
     * 논리 작업 유형과 시작 Instant를 포함해 동일 이력을 추적할 수 있습니다.
     * 사용자 개인정보나 DB 접속 정보는 추가하지 않습니다.
     */
    private Validation invalid(String code, QuestPeriodCalendar.PeriodWindow window) {
        return new Validation(false, "[QUEST_ASSIGNMENT_INTEGRITY] code=" + code
                + ",periodType=" + window.periodType() + ",startAt=" + window.startAt());
    }

    /**
     * 기간·Snapshot의 정합성 검증 결과를 표현합니다.
     * valid=true일 때 reason은 null입니다.
     * valid=false이면 Scheduler 성공 확정을 중단해야 합니다.
     */
    public record Validation(boolean valid, String reason) {
    }

    /**
     * SKIPPED 실행권의 업무 이력 확인 결과를 구분합니다.
     * NOT_SUCCEEDED는 아직 다른 작업이 수행 중일 가능성을 포함합니다.
     * INCONSISTENT는 SUCCEEDED 이력과 DB 업무 상태가 충돌하는 경우입니다.
     */
    public enum AuditOutcome {
        CONSISTENT,
        NOT_SUCCEEDED,
        INCONSISTENT
    }

    /**
     * SKIPPED 실행권의 감사 상태와 불일치 상세 정보를 반환합니다.
     * 정상적인 미확정 이력에는 오류 메시지를 추가하지 않습니다.
     * 데이터 손상은 자동 복구하지 않고 기존 이력을 보존합니다.
     */
    public record ExecutionAudit(AuditOutcome outcome, String detail) {
    }
}
