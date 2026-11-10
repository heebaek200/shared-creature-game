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
    private final SchedulerJobRunner jobRunner;
    private final Clock clock;
    private final String ownerInstanceId = "quest-" + UUID.randomUUID();

    public QuestAssignmentNormalizationService(
            SeasonRepository seasonRepository,
            QuestPeriodRepository periodRepository,
            QuestPeriodCalendar calendar,
            QuestPeriodAssignmentService assignmentService,
            SchedulerJobRunner jobRunner,
            Clock clock) {
        this.seasonRepository = seasonRepository;
        this.periodRepository = periodRepository;
        this.calendar = calendar;
        this.assignmentService = assignmentService;
        this.jobRunner = jobRunner;
        this.clock = clock;
    }


    /** 현재 기간의 스케줄 조회 상태 */
    public enum Outcome { NOT_READY, CHECKED }

    /** 처리한 타입별 실행권 상태와 예외를 보존하는 결과 */
    public record PeriodRun(
            String periodType,
            Instant startAt,
            SchedulerJobRunner.ExecutionResult execution,
            long skippedExpiredPeriods,
            String error
    ) {
    }

    /** 한 번의 기동·자정·5분 점검에서 확인한 현재 기간의 결과 */
    public record Result(Outcome outcome, Long seasonId, List<PeriodRun> periods) {
    }

    /** 과거에 생성되지 않아 의도적으로 건너뛴 기간의 건수와 바깥 경계입니다. */
    private record SkipSummary(long count, Instant first, Instant last) {
    }


    /**
     * 활성화가 확인된 시즌에 대해 현재 일일・주간 기간을 차례로 정상화한다.
     * 첫 번째 기간의 업무 오류는 기록하고 두 번째 기간도 독립적으로 시도.
     * 시간이 만료되었거나 ACTIVE가 아니면 어떤 실행권도 선점하지 않는다.
     */
    public Result normalizeForActiveSeason(Long seasonId) {
        Objects.requireNonNull(seasonId, "seasonId");

        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("퀘스트 전체 정상화는 트랜잭션 밖에서 실행해야 한다.");
        }

        // 시즌 정상화 직후라도 상태, 시각이 바뀌었을 수 있으므로 새로 확인한다.
        Instant now = Instant.now(clock);
        Season season = seasonRepository.findById(seasonId).orElse(null);

        if (season == null || !season.isActiveAt(now)) {
            return new Result(Outcome.NOT_READY, seasonId, List.of());
        }
        Season firstSeason = seasonRepository.findFirstByOrderByStartAtAsc()
                .orElseThrow(() -> new IllegalStateException("첫 시즌 달력 기준이 없습니다."));
        Instant firstStart = firstSeason.getStartAt();

        // 두 배정은 서로 다른 실행권, 트랜잭션을 사용해 한쪽 실패를 다른 쪽에 전파하지 않는다.
        List<PeriodRun> attempts = new ArrayList<>(2);
        for (String periodType : PERIOD_TYPES) {
            try {
                QuestPeriodCalendar.PeriodWindow window = calendar
                        .currentWindow(firstStart, now, periodType)
                        .orElseThrow();
                attempts.add(normalizeOne(firstStart, window));
            } catch (RuntimeException failure) {
                log.error("퀘스트 정상화 준비 실패. type={}, seasonId={}", periodType, seasonId, failure);
                attempts.add(new PeriodRun(periodType, null, null, 0, failure.getMessage()));
            }
        }

        return new Result(Outcome.CHECKED, seasonId, List.copyOf(attempts));
    }

    /**
     * 기간 시작 시각을 Scheduler 논리 키로 사용해 중복 실행을 차단한다.
     * 이미 ACTIVE인 배정은 그대로 두고 정상 성공 이력만 마무리한다.
     * FAILED 배정은 반드시 Scheduler FAILED로 기록해 다음 점검에서 재시도한다.
     */
    private PeriodRun normalizeOne(Instant firstStart, QuestPeriodCalendar.PeriodWindow window) {
        SkipSummary skipped = new SkipSummary(0, null, null);

        try {
            skipped = countExpiredOmissions(firstStart, window);
            SkipSummary summaryForJob = skipped;
            String jobType = "QUEST_ASSIGN_" + window.periodType();

            // 성공 요약은 업무 배정 트랜잭션 Commit이 끝난 다음 실행 이력에 저장
            SchedulerJobRunner.ExecutionResult execution = jobRunner.run(
                    jobType, window.startAt(), ownerInstanceId, LEASE_DURATION,
                    () -> {
                        QuestPeriodAssignmentService.Result result = assignmentService.assign(window);
                        if (result.outcome() == QuestPeriodAssignmentService.Outcome.FAILED
                                || result.outcome() == QuestPeriodAssignmentService.Outcome.NOT_READY) {
                            throw new IllegalStateException("퀘스트 기간 배정 미완료: " + window.periodType() + ", " + result.outcome());
                        }

                        // 생성·기존 ACTIVE만 성공으로 취급하므로 FAILED 실행권을 재선점
                        return jsonSummary(window, result, summaryForJob);
                    });
            return new PeriodRun(window.periodType(), window.startAt(), execution, skipped.count(), null);
        } catch (RuntimeException failure) {
            log.error("퀘스트 배정 실패. type={}, startAt={}", window.periodType(), window.startAt(), failure);
            return new PeriodRun(window.periodType(), window.startAt(), null, skipped.count(), failure.getMessage());
        }
    }

    /**
     * 최초 시즌부터 현재 기간 직전까지의 누락 기간만 집계합니다.
     * 이미 존재하는 과거 ACTIVE·FAILED·CLOSED 기간은 누락에서 제외합니다.
     * 스케줄 정지 중 생략된 기간을 생성하거나 보상을 지급하지 않습니다.
     */
    private SkipSummary countExpiredOmissions(Instant firstStart, QuestPeriodCalendar.PeriodWindow window) {
        long totalPast = window.rotationIndex();
        if (totalPast == 0) {
            return new SkipSummary(0, null, null);
        }
        List<Instant> presentStarts = periodRepository.findHistoricalStartAts(
                window.periodType(), firstStart, window.startAt());
        Set<Long> seen = new HashSet<>();

        // 유효한 KST 기간 경계에 저장된 행만 누락 계산의 기수로 인정합니다.
        for (Instant start : presentStarts) {
            calendar.currentWindow(firstStart, start, window.periodType())
                    .filter(past -> past.startAt().equals(start) && past.rotationIndex() < totalPast)
                    .ifPresent(past -> seen.add(past.rotationIndex()));
        }
        long missing = totalPast - seen.size();
        if (missing == 0) {
            return new SkipSummary(0, null, null);
        }

        // 누락 기간의 처음과 끝을 찾되 기간 전체에 대한 소급 INSERT는 수행하지 않습니다.
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
     * 첫 시즌 KST 날짜와 논리 인덱스에서 기간 시작 Instant를 복원합니다.
     * 주간은 7일 간격, 일일은 1일 간격이며 UTC 고정 시간 계산을 피합니다.
     * 다른 JVM 시간대에서도 동일한 논리 시작 시각을 반환합니다.
     */
    private Instant periodStartAt(Instant firstStart, String periodType, long index) {
        long stepDays = "WEEKLY".equals(periodType) ? 7L : 1L;
        LocalDate firstDate = firstStart.atZone(KST).toLocalDate();
        return firstDate.plusDays(Math.multiplyExact(index, stepDays))
                .atStartOfDay(KST).toInstant();
    }

    /**
     * 이미 Commit된 퀘스트 배정과 생략된 과거 기간을 JSON 실행 요약으로 남깁니다.
     * 모든 문자열은 고정 enum 또는 ISO Instant로 구성되어 JSON 인코딩이 안전합니다.
     * CREATED/ALREADY_ACTIVE만 여기까지 도달하고 나머지는 실패 이력으로 처리합니다.
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
     * JSON 요약에 nullable ISO Instant를 변환합니다.
     * null은 문자열이 아닌 JSON null 토큰으로 기록합니다.
     * Instant의 ISO 표현에는 따옴표나 줄바꿈이 포함되지 않습니다.
     */
    private String jsonInstant(Instant value) {
        return value == null ? "null" : "\"" + value + "\"";
    }

}
