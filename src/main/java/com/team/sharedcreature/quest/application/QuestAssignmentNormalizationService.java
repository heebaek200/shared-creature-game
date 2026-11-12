package com.team.sharedcreature.quest.application;

import com.team.sharedcreature.admin.application.SchedulerJobRunner;
import com.team.sharedcreature.quest.infrastructure.QuestPeriodRepository;
import com.team.sharedcreature.season.domain.Season;
import com.team.sharedcreature.season.infrastructure.SeasonRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.*;
import java.util.*;

/**
 * 현재 일일·주간 퀘스트를 Scheduler 실행권과 실제 기간 데이터에 맞춰 정상화합니다.
 * 실행권과 실제 업무 Commit을 분리하고 이미 성공한 결과는 변경하지 않습니다.
 * SUCCEEDED 이력과 DB 상태가 일치하지 않으면 로그와 반환 결과에 오류를 기록합니다.
 */
@Slf4j
@Service
public class QuestAssignmentNormalizationService {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    private static final Duration LEASE_DURATION = Duration.ofMinutes(3);
    private static final List<String> PERIOD_TYPES = List.of("DAILY", "WEEKLY");

    private final SeasonRepository seasonRepository;
    private final QuestPeriodRepository periodRepository;
    private final QuestPeriodCalendar calendar;
    private final QuestPeriodAssignmentService assignmentService;
    private final QuestAssignmentIntegrityService integrityService;
    private final SchedulerJobRunner jobRunner;
    private final Clock clock;
    private final String ownerInstanceId = "quest-" + UUID.randomUUID();

    /**
     * 시즌 달력·배정 서비스와 성공 이력 정합성 검사기를 주입받습니다.
     * Scheduler 실행권은 공통 JobRunner에서만 선점하고 갱신합니다.
     * 검증은 별도 읽기 전용 트랜잭션에서 수행되어 업무 Commit을 되돌리지 않습니다.
     */
    public QuestAssignmentNormalizationService(
            SeasonRepository seasonRepository,
            QuestPeriodRepository periodRepository,
            QuestPeriodCalendar calendar,
            QuestPeriodAssignmentService assignmentService,
            QuestAssignmentIntegrityService integrityService,
            SchedulerJobRunner jobRunner,
            Clock clock
    ) {
        this.seasonRepository = seasonRepository;
        this.periodRepository = periodRepository;
        this.calendar = calendar;
        this.assignmentService = assignmentService;
        this.integrityService = integrityService;
        this.jobRunner = jobRunner;
        this.clock = clock;
    }

    /** 현재 기간의 스케줄 조회 상태를 나타냅니다. */
    public enum Outcome { NOT_READY, CHECKED }

    /**
     * 기간별 실행권 상태와 감지한 오류를 보관합니다.
     * execution=SKIPPED이면서 error가 존재하면 이력·DB 정합성을 점검해야 합니다.
     * SKIPPED·error=null이면 이미 정상 완료됐거나 다른 실행자가 처리 중입니다.
     */
    public record PeriodRun(
            String periodType,
            Instant startAt,
            SchedulerJobRunner.ExecutionResult execution,
            long skippedExpiredPeriods,
            String error
    ) {
    }

    /** 한 번의 기동·자정·5분 점검에서 확인한 현재 기간의 결과를 보관합니다. */
    public record Result(Outcome outcome, Long seasonId, List<PeriodRun> periods) {
    }

    /** 과거에 생성되지 않아 의도적으로 건너뛴 기간의 건수와 바깥 경계를 보관합니다. */
    private record SkipSummary(long count, Instant first, Instant last) {
    }

    /**
     * 활성화가 확인된 시즌에 대해 현재 일일·주간 기간을 차례대로 정상화합니다.
     * DAILY 작업에서 실패가 발생해도 WEEKLY를 별도로 실행하고 기록합니다.
     * ACTIVE 시즌이 아니면 어떤 실행권도 선점하지 않습니다.
     */
    public Result normalizeForActiveSeason(Long seasonId) {
        Objects.requireNonNull(seasonId, "seasonId");
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("퀘스트 전체 정상화는 트랜잭션 밖에서 실행해야 합니다.");
        }

        // 시즌 정상화 직후에도 시간·상태가 바뀔 수 있어 다시 확인합니다.
        Instant now = Instant.now(clock);
        Season season = seasonRepository.findById(seasonId).orElse(null);
        if (season == null || !season.isActiveAt(now)) {
            return new Result(Outcome.NOT_READY, seasonId, List.of());
        }
        Season firstSeason = seasonRepository.findFirstByOrderByStartAtAsc()
                .orElseThrow(() -> new IllegalStateException("첫 시즌 달력 기준이 없습니다."));
        Instant firstStart = firstSeason.getStartAt();

        // 두 배정은 독립 실행권을 사용하여 하나의 실패가 다른 하나에 영향을 주지 않습니다.
        List<PeriodRun> attempts = new ArrayList<>(2);
        for (String periodType : PERIOD_TYPES) {
            try {
                QuestPeriodCalendar.PeriodWindow window = calendar
                        .currentWindow(firstStart, now, periodType)
                        .orElseThrow();
                attempts.add(normalizeOne(seasonId, firstStart, window));
            } catch (RuntimeException failure) {
                log.error("퀘스트 정상화 준비 실패. type={}, seasonId={}", periodType, seasonId, failure);
                attempts.add(new PeriodRun(periodType, null, null, 0, failure.getMessage()));
            }
        }
        return new Result(Outcome.CHECKED, seasonId, List.copyOf(attempts));
    }

    /**
     * 실행권이 있으면 배정 Commit 뒤 Snapshot 무결성까지 확인한 후 성공 기록합니다.
     * 이미 SUCCEEDED여서 SKIPPED됐다면 업무 데이터의 현재 상태를 감사합니다.
     * RUNNING으로 인한 SKIPPED는 정합성 위반으로 판단하지 않습니다.
     */
    private PeriodRun normalizeOne(
            Long seasonId, Instant firstStart, QuestPeriodCalendar.PeriodWindow window
    ) {
        SkipSummary skipped = new SkipSummary(0, null, null);
        try {
            skipped = countExpiredOmissions(firstStart, window);
            SkipSummary summaryForJob = skipped;
            String jobType = "QUEST_ASSIGN_" + window.periodType();

            // 배정 Service는 독립 Commit을 종료한 뒤 결과를 반환합니다.
            SchedulerJobRunner.ExecutionResult execution = jobRunner.run(
                    jobType, window.startAt(), ownerInstanceId, LEASE_DURATION,
                    () -> {
                        QuestPeriodAssignmentService.Result result = assignmentService.assign(window);
                        if (result.outcome() == QuestPeriodAssignmentService.Outcome.FAILED
                                || result.outcome() == QuestPeriodAssignmentService.Outcome.NOT_READY) {
                            throw new IllegalStateException("퀘스트 기간 배정 미완료: "
                                    + window.periodType() + ", " + result.outcome());
                        }

                        // 기간 ACTIVE만으로 성공을 확정하지 않고 실제 슬롯 3/7건을 검사합니다.
                        QuestAssignmentIntegrityService.Validation checked =
                                integrityService.verifyCommittedAssignment(window, seasonId);
                        if (!checked.valid()) {
                            throw new IllegalStateException(checked.reason());
                        }
                        return jsonSummary(window, result, summaryForJob);
                    });

            // 이미 완료된 실행권과 현재 데이터가 어긋난 경우 SKIPPED 성공으로 위장하지 않습니다.
            if (execution == SchedulerJobRunner.ExecutionResult.SKIPPED) {
                QuestAssignmentIntegrityService.ExecutionAudit audit =
                        integrityService.inspectSkippedExecution(jobType, window, seasonId);
                if (audit.outcome() == QuestAssignmentIntegrityService.AuditOutcome.INCONSISTENT) {
                    log.error("퀘스트 Scheduler 성공 이력과 저장 데이터 불일치. jobType={}, scheduledFor={}, detail={}",
                            jobType, window.startAt(), audit.detail());
                    return new PeriodRun(window.periodType(), window.startAt(), execution,
                            skipped.count(), audit.detail());
                }
            }
            return new PeriodRun(window.periodType(), window.startAt(), execution, skipped.count(), null);
        } catch (RuntimeException failure) {
            log.error("퀘스트 배정 또는 정합성 검사 실패. type={}, startAt={}",
                    window.periodType(), window.startAt(), failure);
            return new PeriodRun(window.periodType(), window.startAt(), null,
                    skipped.count(), failure.getMessage());
        }
    }

    /**
     * 최초 시즌부터 현재 기간 직전까지 생성하지 않은 기간만 집계합니다.
     * 이미 존재하는 과거 ACTIVE·FAILED·CLOSED 기간은 누락에서 제외합니다.
     * 만료된 기간을 소급 INSERT하거나 보상을 이월하지 않습니다.
     */
    private SkipSummary countExpiredOmissions(Instant firstStart, QuestPeriodCalendar.PeriodWindow window) {
        long totalPast = window.rotationIndex();
        if (totalPast == 0) {
            return new SkipSummary(0, null, null);
        }
        List<Instant> presentStarts = periodRepository.findHistoricalStartAts(
                window.periodType(), firstStart, window.startAt());
        Set<Long> seen = new HashSet<>();

        // 실제 KST 경계에 저장된 행만 누락 수에서 제외합니다.
        for (Instant start : presentStarts) {
            calendar.currentWindow(firstStart, start, window.periodType())
                    .filter(past -> past.startAt().equals(start) && past.rotationIndex() < totalPast)
                    .ifPresent(past -> seen.add(past.rotationIndex()));
        }
        long missing = totalPast - seen.size();
        if (missing == 0) {
            return new SkipSummary(0, null, null);
        }

        // 과거 기간의 최솟값과 최댓값만 결과 요약에 저장합니다.
        long firstMissing = 0;
        while (seen.contains(firstMissing)) {
            firstMissing++;
        }
        long lastMissing = totalPast - 1;
        while (seen.contains(lastMissing)) {
            lastMissing--;
        }
        return new SkipSummary(
                missing, periodStartAt(firstStart, window.periodType(), firstMissing),
                periodStartAt(firstStart, window.periodType(), lastMissing));
    }

    /**
     * 최초 시즌 KST 날짜와 논리 회전 인덱스로 기간 시작 시각을 복원합니다.
     * 주간은 7일 간격이고 일일은 1일 간격입니다.
     * JVM 기본 시간대와 무관하게 동일한 Instant를 사용합니다.
     */
    private Instant periodStartAt(Instant firstStart, String periodType, long index) {
        long stepDays = "WEEKLY".equals(periodType) ? 7L : 1L;
        LocalDate firstDate = firstStart.atZone(KST).toLocalDate();
        return firstDate.plusDays(Math.multiplyExact(index, stepDays))
                .atStartOfDay(KST).toInstant();
    }

    /**
     * 모든 Snapshot 검사까지 통과한 배정과 생략된 과거 기간을 JSON 요약으로 기록합니다.
     * 문자열 값은 통제된 기간 타입·ISO Instant에서 생성되며 원래 형식을 유지합니다.
     * 실패하거나 검사되지 않은 배정은 여기서 SUCCEEDED 요약을 만들 수 없습니다.
     */
    private String jsonSummary(
            QuestPeriodCalendar.PeriodWindow window,
            QuestPeriodAssignmentService.Result result,
            SkipSummary skipped
    ) {
        return """
                {"periodType":"%s","periodStart":"%s","outcome":"%s",\
                "periodId":%d,"assignmentCount":%d,"skippedExpiredCount":%d,\
                "skippedFrom":%s,"skippedThrough":%s}
                """.formatted(
                window.periodType(), window.startAt(), result.outcome(),
                result.periodId(), result.assignmentCount(), skipped.count(),
                jsonInstant(skipped.first()), jsonInstant(skipped.last()));
    }

    /**
     * nullable Instant를 JSON 값으로 변환합니다.
     * 값이 없으면 문자열이 아닌 JSON null 토큰을 사용합니다.
     * 값이 있으면 UTC ISO 형식에 따옴표를 붙입니다.
     */
    private String jsonInstant(Instant value) {
        return value == null ? "null" : "\"" + value + "\"";
    }
}
