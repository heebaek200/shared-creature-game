package com.team.sharedcreature.quest.application;

import com.team.sharedcreature.quest.domain.QuestPeriod;
import com.team.sharedcreature.quest.infrastructure.QuestAssignmentRepository;
import com.team.sharedcreature.quest.infrastructure.QuestPeriodRepository;
import com.team.sharedcreature.quest.infrastructure.QuestTemplateRepository;
import com.team.sharedcreature.quest.infrastructure.QuestTemplateVersionRepository;
import com.team.sharedcreature.season.application.SeasonQueryService;
import com.team.sharedcreature.season.domain.Season;
import com.team.sharedcreature.season.infrastructure.SeasonRepository;
import org.junit.jupiter.api.Test;
import org.springframework.dao.CannotAcquireLockException;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * 퀘스트 기간의 INSERT Gap Lock 회피와 잠금 실패 재시도를 회귀 검증합니다.
 * 신규 기간에는 잠금 조회를 하지 않고 기존 기간에서만 잠금 조회를 수행해야 합니다.
 * 실 DB의 데드락 재현은 QuestSchedulerRecoveryIntegrationTest에서 별도로 확인합니다.
 */
class QuestPeriodLockRecoveryTest {

    private static final Instant START = Instant.parse("2026-11-01T15:00:00Z");
    private static final Instant NOW = Instant.parse("2026-11-09T18:00:00Z");
    private static final QuestPeriodCalendar.PeriodWindow WINDOW =
            new QuestPeriodCalendar.PeriodWindow(
                    "DAILY", LocalDate.of(2026, 11, 10),
                    Instant.parse("2026-11-09T15:00:00Z"),
                    Instant.parse("2026-11-10T15:00:00Z"), 8);

    private final QuestPeriodPersistenceService persistence = mock(QuestPeriodPersistenceService.class);
    private final QuestPeriodAssignmentService assignmentService =
            new QuestPeriodAssignmentService(persistence);

    /**
     * MySQL 데드락이 발생해도 이미 롤백된 트랜잭션을 다시 사용하지 않습니다.
     * 조정 서비스에서 실제 영속화 메서드를 재호출해야 합니다.
     * 두 번째 시도가 성공하면 최초 잠금 오류는 외부로 전파되지 않습니다.
     */
    @Test
    void retriesDeadlockInFreshPersistenceCall() {
        var result = new QuestPeriodAssignmentService.Result(
                QuestPeriodAssignmentService.Outcome.CREATED, 12L, 3);
        when(persistence.assign(WINDOW))
                .thenThrow(new CannotAcquireLockException("deadlock"))
                .thenReturn(result);

        assertSame(result, assignmentService.assign(WINDOW));
        verify(persistence, times(2)).assign(WINDOW);
    }

    /**
     * 락 경합이 반복되어도 무한 재시도하지 않습니다.
     * 세 번 모두 실패하면 마지막 잠금 실패를 호출자에게 전파합니다.
     * 스케줄러의 FAILED 이력과 차후 5분 점검은 상위 계층에서 처리합니다.
     */
    @Test
    void stopsAfterThreeDeadlockAttempts() {
        when(persistence.assign(WINDOW))
                .thenThrow(new CannotAcquireLockException("deadlock"));

        assertThrows(CannotAcquireLockException.class, () -> assignmentService.assign(WINDOW));
        verify(persistence, times(3)).assign(WINDOW);
    }

    /**
     * 아직 생성되지 않은 기간은 일반 조회만 수행해야 합니다.
     * Selector가 실패하면 INSERT 전에 트랜잭션을 중단하고 예외를 전파합니다.
     * 기존 구현처럼 부재 키에 FOR UPDATE를 걸면 이 검증이 실패합니다.
     */
    @Test
    void missingPeriodDoesNotAcquireWriteLock() {
        Fixture f = fixture();
        when(f.periodRepository().findByPeriodTypeAndStartAt("DAILY", WINDOW.startAt()))
                .thenReturn(Optional.empty());
        when(f.selector().select(WINDOW)).thenThrow(
                new QuestTemplateSelectionService.SelectionException(
                        QuestTemplateSelectionService.FailureCode.INSUFFICIENT_ACTIVE_TEMPLATES,
                        "템플릿 부족"));

        assertThrows(QuestTemplateSelectionService.SelectionException.class,
                () -> f.service().assign(WINDOW));
        verify(f.periodRepository(), never())
                .findForUpdateByPeriodTypeAndStartAt(anyString(), any());
    }

    /**
     * 이미 존재하는 ACTIVE 행은 여전히 배타 잠금으로 재확인해야 합니다.
     * 이 경우 새 INSERT를 수행하지 않고 저장된 Snapshot 건수를 반환합니다.
     * 신규 기간만 잠금 조회를 피하는지 함께 검증합니다.
     */
    @Test
    void existingPeriodRetainsWriteLock() {
        Fixture f = fixture();
        QuestPeriod period = mock(QuestPeriod.class);
        when(f.periodRepository().findByPeriodTypeAndStartAt("DAILY", WINDOW.startAt()))
                .thenReturn(Optional.of(period));
        when(f.periodRepository().findForUpdateByPeriodTypeAndStartAt("DAILY", WINDOW.startAt()))
                .thenReturn(Optional.of(period));
        when(period.getStatus()).thenReturn("ACTIVE");
        when(period.getId()).thenReturn(17L);
        when(f.assignmentRepository().countByQuestPeriodId(17L)).thenReturn(3L);

        var result = f.service().assign(WINDOW);
        assertEquals(QuestPeriodAssignmentService.Outcome.ALREADY_ACTIVE, result.outcome());
        assertEquals(Long.valueOf(17L), result.periodId());
        verify(f.periodRepository()).findForUpdateByPeriodTypeAndStartAt("DAILY", WINDOW.startAt());
    }

    /**
     * 실제 영속화 서비스에 필요한 시즌·기간 의존성을 Mock으로 준비합니다.
     * 시즌 달력은 테스트의 현재 시각과 동일한 DAILY 창을 반환하도록 합니다.
     * JDBC 연결 없이 잠금 조회 순서만 결정적으로 검증합니다.
     */
    private Fixture fixture() {
        var seasonQuery = mock(SeasonQueryService.class);
        var seasons = mock(SeasonRepository.class);
        var calendar = mock(QuestPeriodCalendar.class);
        var selector = mock(QuestTemplateSelectionService.class);
        var periods = mock(QuestPeriodRepository.class);
        var assignments = mock(QuestAssignmentRepository.class);
        var templates = mock(QuestTemplateRepository.class);
        var versions = mock(QuestTemplateVersionRepository.class);
        var season = mock(Season.class);

        // 트랜잭션 프록시는 사용하지 않고 시즌 적격성 검사에 필요한 값만 설정합니다.
        when(seasonQuery.findCurrentSeasonForShareIfPresent()).thenReturn(Optional.of(season));
        when(season.getStartAt()).thenReturn(START);
        when(season.getEndAt()).thenReturn(START.plusSeconds(28L * 24 * 3600));
        when(seasons.findFirstByOrderByStartAtAsc()).thenReturn(Optional.of(season));
        when(calendar.currentWindow(START, NOW, "DAILY")).thenReturn(Optional.of(WINDOW));

        QuestPeriodPersistenceService service = new QuestPeriodPersistenceService(
                seasonQuery, seasons, calendar, selector, periods,
                assignments, templates, versions, Clock.fixed(NOW, ZoneOffset.UTC));
        return new Fixture(service, periods, assignments, selector);
    }

    /** 테스트에서 검증할 영속화 서비스와 Mock Repository를 보관합니다. */
    private record Fixture(
            QuestPeriodPersistenceService service,
            QuestPeriodRepository periodRepository,
            QuestAssignmentRepository assignmentRepository,
            QuestTemplateSelectionService selector
    ) {}
}
