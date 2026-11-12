package com.team.sharedcreature.quest.application;

import com.team.sharedcreature.quest.domain.QuestAssignment;
import com.team.sharedcreature.quest.domain.QuestPeriod;
import com.team.sharedcreature.quest.infrastructure.QuestAssignmentRepository;
import com.team.sharedcreature.quest.infrastructure.QuestPeriodRepository;
import com.team.sharedcreature.quest.infrastructure.QuestTemplateRepository;
import com.team.sharedcreature.quest.infrastructure.QuestTemplateVersionRepository;
import com.team.sharedcreature.season.application.SeasonQueryService;
import com.team.sharedcreature.season.domain.Season;
import com.team.sharedcreature.season.infrastructure.SeasonRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/**
 * 퀘스트 기간과 배정 Snapshot을 실제 DB에 기록합니다.
 * 성공과 실패 기록은 각각 독립 트랜잭션에서 Commit합니다.
 * 기존 시즌 공유 잠금을 먼저 획득하여 종료 Snapshot과 충돌하지 않게 합니다.
 */
@Service
public class QuestPeriodPersistenceService {

    private final SeasonQueryService seasonQueryService;
    private final SeasonRepository seasonRepository;
    private final QuestPeriodCalendar calendar;
    private final QuestTemplateSelectionService selector;
    private final QuestPeriodRepository periodRepository;
    private final QuestAssignmentRepository assignmentRepository;
    private final QuestTemplateRepository templateRepository;
    private final QuestTemplateVersionRepository versionRepository;
    private final Clock clock;

    /**
     * 시즌 조회·기간 계산·템플릿 선정과 영속화 의존성을 주입합니다.
     * Scheduler 트리거는 이 서비스에 직접 결합하지 않습니다.
     * 모든 데이터 변경 메서드는 반드시 독립 트랜잭션에서 동작합니다.
     */
    public QuestPeriodPersistenceService(
            SeasonQueryService seasonQueryService,
            SeasonRepository seasonRepository,
            QuestPeriodCalendar calendar,
            QuestTemplateSelectionService selector,
            QuestPeriodRepository periodRepository,
            QuestAssignmentRepository assignmentRepository,
            QuestTemplateRepository templateRepository,
            QuestTemplateVersionRepository versionRepository,
            Clock clock
    ) {
        this.seasonQueryService = seasonQueryService;
        this.seasonRepository = seasonRepository;
        this.calendar = calendar;
        this.selector = selector;
        this.periodRepository = periodRepository;
        this.assignmentRepository = assignmentRepository;
        this.templateRepository = templateRepository;
        this.versionRepository = versionRepository;
        this.clock = clock;
    }

    /**
     * 현재 유효한 기간의 배정 전체를 하나의 Commit으로 저장합니다.
     * 이미 ACTIVE이면 원본 Snapshot을 그대로 보존합니다.
     * FAILED는 같은 기간 행을 잠가 재시도하며 부분 INSERT 실패 시 전체 롤백합니다.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public QuestPeriodAssignmentService.Result assign(QuestPeriodCalendar.PeriodWindow window) {
        Objects.requireNonNull(window, "window");
        // 시즌 공유 잠금을 얻은 뒤 기간 시각을 판단해야 잠금 대기 중의 날짜 변경을 감지합니다.
        Optional<Season> currentSeason = eligibleSeason(window);
        if (currentSeason.isEmpty()) {
            return new QuestPeriodAssignmentService.Result(
                    QuestPeriodAssignmentService.Outcome.NOT_READY, null, 0);
        }
        Instant now = Instant.now(clock);

        // 시즌 공유 잠금을 확보한 뒤 기존 기간을 잠가 동시 재실행을 직렬화합니다.
        QuestPeriod period = findExistingPeriodForUpdate(window);
        // 기존 기간 행의 잠금 대기 중 날짜가 바뀌었을 수 있으므로 NOW를 반드시 다시 읽습니다.
        now = Instant.now(clock);
        if (!isWithinWindow(window, currentSeason.orElseThrow(), now)) {
            return new QuestPeriodAssignmentService.Result(
                    QuestPeriodAssignmentService.Outcome.NOT_READY, null, 0);
        }
        if (period != null && "ACTIVE".equals(period.getStatus())) {
            return new QuestPeriodAssignmentService.Result(
                    QuestPeriodAssignmentService.Outcome.ALREADY_ACTIVE, period.getId(),
                    assignmentRepository.countByQuestPeriodId(period.getId()));
        }
        if (period != null && assignmentRepository.countByQuestPeriodId(period.getId()) != 0) {
            throw new IllegalStateException("미확정 기간에 기존 배정이 남아 있습니다: " + period.getId());
        }

        // 선정 실패는 외부 조정 서비스에 전파해 롤백 후 별도 FAILED 이력을 기록합니다.
        QuestTemplateSelectionService.SelectionBatch batch = selector.select(window);
        int expectedSlots = "DAILY".equals(window.periodType()) ? 3 : 7;
        if (!window.periodType().equals(batch.periodType()) || batch.slots().size() != expectedSlots) {
            throw new IllegalStateException("퀘스트 슬롯 전체가 준비되지 않았습니다: " + window.periodType());
        }
        // 템플릿 조회 도중 날짜가 바뀌었다면 아직 DB를 변경하지 않았으므로 즉시 중단합니다.
        now = Instant.now(clock);
        if (!isWithinWindow(window, currentSeason.orElseThrow(), now)) {
            return new QuestPeriodAssignmentService.Result(
                    QuestPeriodAssignmentService.Outcome.NOT_READY, null, 0);
        }
        if (period == null) {
            period = periodRepository.saveAndFlush(QuestPeriod.pending(
                    currentSeason.orElseThrow(), window.periodType(), window.periodDate(),
                    window.startAt(), window.endAt(), now));
        } else {
            period.prepareRetry(now);
        }

        for (QuestTemplateSelectionService.SelectedQuest selected : batch.slots()) {
            var template = templateRepository.getReferenceById(selected.templateId());
            var version = versionRepository.getReferenceById(selected.templateVersionId());
            // 원본 템플릿과 별개로 선택 당시의 제목·조건·보상을 영구 Snapshot으로 고정합니다.
            assignmentRepository.save(QuestAssignment.capture(
                    period, template, version, selected.slotNumber(),
                    new QuestAssignment.Snapshot(
                            selected.titleSnapshot(), selected.descriptionSnapshot(),
                            selected.difficultySnapshot(), selected.verificationTypeSnapshot(),
                            selected.verificationParametersSnapshot(), selected.creatureExperienceSnapshot(),
                            selected.contributionScoreSnapshot()), now));
        }
        period.activate(now);
        assignmentRepository.flush();
        periodRepository.flush();
        // 마지막 Flush 직후 경계가 지났다면 예외로 REQUIRES_NEW 트랜잭션 전체를 롤백합니다.
        ensureWindowOpenOrRollback(window, currentSeason.orElseThrow());
        return new QuestPeriodAssignmentService.Result(
                QuestPeriodAssignmentService.Outcome.CREATED, period.getId(), batch.slots().size());
    }

    /**
     * 템플릿 선정 오류를 별도 Commit으로 FAILED 기간에 기록합니다.
     * 동시 실행자가 이미 ACTIVE로 확정한 기간은 절대 FAILED로 되돌리지 않습니다.
     * 시즌 또는 기간이 만료됐다면 과거 FAILED 행을 소급 생성하지 않습니다.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public QuestPeriodAssignmentService.Result recordSelectionFailure(
            QuestPeriodCalendar.PeriodWindow window, String code, String detail
    ) {
        Objects.requireNonNull(window, "window");
        // 시즌 공유 잠금을 얻은 뒤 기간 시각을 판단해야 잠금 대기 중의 날짜 변경을 감지합니다.
        Optional<Season> currentSeason = eligibleSeason(window);
        if (currentSeason.isEmpty()) {
            return new QuestPeriodAssignmentService.Result(
                    QuestPeriodAssignmentService.Outcome.NOT_READY, null, 0);
        }
        Instant now = Instant.now(clock);

        // 실패 행도 동일 UNIQUE 기간을 사용해 재실행으로 중복 생성되지 않게 합니다.
        QuestPeriod period = findExistingPeriodForUpdate(window);
        // 실패 기록에서도 기존 행의 배타 잠금 대기 직후 NOW 값을 다시 읽습니다.
        now = Instant.now(clock);
        if (!isWithinWindow(window, currentSeason.orElseThrow(), now)) {
            return new QuestPeriodAssignmentService.Result(
                    QuestPeriodAssignmentService.Outcome.NOT_READY, null, 0);
        }
        if (period != null && "ACTIVE".equals(period.getStatus())) {
            return new QuestPeriodAssignmentService.Result(
                    QuestPeriodAssignmentService.Outcome.ALREADY_ACTIVE, period.getId(),
                    assignmentRepository.countByQuestPeriodId(period.getId()));
        }
        // 실패 이력 INSERT/UPDATE도 마찬가지로 만료된 날짜에 소급 기록하지 않습니다.
        now = Instant.now(clock);
        if (!isWithinWindow(window, currentSeason.orElseThrow(), now)) {
            return new QuestPeriodAssignmentService.Result(
                    QuestPeriodAssignmentService.Outcome.NOT_READY, null, 0);
        }
        if (period == null) {
            period = periodRepository.save(QuestPeriod.pending(
                    currentSeason.orElseThrow(), window.periodType(), window.periodDate(),
                    window.startAt(), window.endAt(), now));
        } else if (assignmentRepository.countByQuestPeriodId(period.getId()) != 0) {
            throw new IllegalStateException("부분 배정이 남아 있는 기간을 FAILED로 덮어쓸 수 없습니다: " + period.getId());
        }
        period.fail(code, detail, now);
        periodRepository.flush();
        // 실패 행도 트랜잭션 종료 전 최종 검사해 만료 경계에서는 Rollback합니다.
        ensureWindowOpenOrRollback(window, currentSeason.orElseThrow());
        return new QuestPeriodAssignmentService.Result(
                QuestPeriodAssignmentService.Outcome.FAILED, period.getId(), 0);
    }

    /**
     * 경쟁 트랜잭션에서 UNIQUE 충돌이 발생했을 때 확정된 현재 상태를 재조회합니다.
     * 기존 ACTIVE 또는 FAILED 행이 보이면 상태를 전달하고 그 외 오류는 숨기지 않습니다.
     * 이전 물리 트랜잭션이 종료된 뒤 새로운 트랜잭션으로 조회해야 합니다.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = true)
    public Optional<QuestPeriodAssignmentService.Result> readCommittedOutcome(
            QuestPeriodCalendar.PeriodWindow window
    ) {
        return periodRepository.findByPeriodTypeAndStartAt(window.periodType(), window.startAt())
                .flatMap(period -> switch (period.getStatus()) {
                    case "ACTIVE" -> Optional.of(new QuestPeriodAssignmentService.Result(
                            QuestPeriodAssignmentService.Outcome.ALREADY_ACTIVE, period.getId(),
                            assignmentRepository.countByQuestPeriodId(period.getId())));
                    case "FAILED" -> Optional.of(new QuestPeriodAssignmentService.Result(
                            QuestPeriodAssignmentService.Outcome.FAILED, period.getId(), 0));
                    default -> Optional.empty();
                });
    }

    /**
     * 기간 행이 존재할 때만 배타 잠금을 획득합니다.
     * MySQL REPEATABLE READ에서 없는 UNIQUE 키를 FOR UPDATE로 조회하면 Gap Lock이 생길 수 있습니다.
     * 새 기간은 잠금 없는 조회 후 INSERT하며 최종 경합은 UNIQUE 제약에서 처리합니다.
     *
     * @param window 현재 유효한 퀘스트 기간
     * @return 기존 잠금 행 또는 신규 기간을 나타내는 null
     */
    private QuestPeriod findExistingPeriodForUpdate(QuestPeriodCalendar.PeriodWindow window) {
        // 부재 키에 대한 잠금 읽기를 회피하여 DAILY/WEEKLY 동시 INSERT를 허용합니다.
        if (periodRepository.findByPeriodTypeAndStartAt(
                window.periodType(), window.startAt()).isEmpty()) {
            return null;
        }

        // 기존 FAILED/ACTIVE 행은 SELECT FOR UPDATE로 읽어 상태 전이를 직렬화합니다.
        return periodRepository.findForUpdateByPeriodTypeAndStartAt(
                window.periodType(), window.startAt()).orElse(null);
    }

    /**
     * 조회 시점의 활성 시즌과 첫 시즌 기준으로 창의 유효성을 재검사합니다.
     * 시즌 밖 기간이나 이미 만료된 기간은 배정하지 않습니다.
     * 주입받은 Clock과 DB의 최초 시즌 시작 경계만 업무 시각 판단에 사용합니다.
     */
    private Optional<Season> eligibleSeason(QuestPeriodCalendar.PeriodWindow window) {
        // 먼저 공유 잠금을 확보하고, 이후의 시계 값으로 일일·주간 기간을 판정합니다.
        Optional<Season> season = seasonQueryService.findCurrentSeasonForShareIfPresent();
        Instant now = Instant.now(clock);
        if (season.isEmpty() || now.isBefore(window.startAt()) || !now.isBefore(window.endAt())) {
            return Optional.empty();
        }
        Season active = season.orElseThrow();
        if (window.startAt().isBefore(active.getStartAt()) || window.endAt().isAfter(active.getEndAt())) {
            return Optional.empty();
        }

        // 호출자가 잘못 만든 회전 인덱스나 기간 경계를 저장하지 않도록 다시 계산합니다.
        Optional<Season> first = seasonRepository.findFirstByOrderByStartAtAsc();
        if (first.isEmpty() || calendar.currentWindow(first.get().getStartAt(), now,
                window.periodType()).filter(window::equals).isEmpty()) {
            return Optional.empty();
        }
        return season;
    }

    /**
     * 이미 잠근 시즌과 요청 기간이 주어진 시각에도 유효한지 검사합니다.
     * 시즌 상태는 공유 잠금을 확보한 SeasonQueryService가 검증했습니다.
     * 기간의 시작과 종료는 모두 [startAt, endAt) 반열린 구간으로 판정합니다.
     */
    private boolean isWithinWindow(
            QuestPeriodCalendar.PeriodWindow window, Season season, Instant now
    ) {
        return !now.isBefore(window.startAt()) && now.isBefore(window.endAt())
                && !now.isBefore(season.getStartAt()) && now.isBefore(season.getEndAt());
    }

    /**
     * Flush 이후에도 날짜가 바뀌지 않았는지 확인해 모든 쓰기를 원자적으로 되돌립니다.
     * NOT_READY를 정상 반환하면 트랜잭션이 Commit되므로 반드시 예외를 발생시킵니다.
     * 바깥 조정 서비스는 실제 트랜잭션이 Rollback된 다음 이 예외를 NOT_READY로 바꿉니다.
     */
    private void ensureWindowOpenOrRollback(
            QuestPeriodCalendar.PeriodWindow window, Season season
    ) {
        if (!isWithinWindow(window, season, Instant.now(clock))) {
            throw new WindowExpiredDuringPersistenceException(window.periodType(), window.startAt());
        }
    }

    /**
     * 기간 만료가 DB 쓰기 도중 발생해 전체 Rollback이 필요한 상태입니다.
     * 성공/실패 행 중 어느 쪽도 부분 Commit하지 않도록 런타임 예외로 표현합니다.
     * 상위 조정 서비스에서 NOT_READY로 변환하되 원본 트랜잭션 안에서는 잡지 않습니다.
     */
    static final class WindowExpiredDuringPersistenceException extends RuntimeException {
        WindowExpiredDuringPersistenceException(String type, Instant startAt) {
            super("퀘스트 저장 중 기간이 종료되었습니다. type=" + type + ", startAt=" + startAt);
        }
    }
}
