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

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.*;

/**
 * 이미 존재하는 퀘스트 기간의 배타 잠금 대기 도중 날짜 경계가 바뀌는 경우를 재현합니다.
 * 이전 R-1 테스트는 시즌 공유 잠금·템플릿 선정 중 경계에 집중했습니다.
 * 기존 ACTIVE 기간의 조기 반환과 실패 기록 경로 모두 만료를 인식하는지 검사합니다.
 */
class QuestPeriodExistingLockBoundaryTest {

    private static final Instant FIRST_START = Instant.parse("2026-11-01T15:00:00Z");
    private static final Instant NOW = Instant.parse("2026-11-10T14:59:59Z");
    private static final Instant EXPIRED = Instant.parse("2026-11-10T15:00:00Z");
    private static final QuestPeriodCalendar.PeriodWindow WINDOW =
            new QuestPeriodCalendar.PeriodWindow(
                    "DAILY", LocalDate.of(2026, 11, 10),
                    Instant.parse("2026-11-09T15:00:00Z"), EXPIRED, 8L);

    /**
     * 이미 ACTIVE인 기간 행을 배타 잠금하는 동안 자정이 지났다고 가정합니다.
     * 과거 NOW 값으로 검사하면 ALREADY_ACTIVE를 반환하여 테스트가 실패합니다.
     * 잠금 후 Clock을 읽는 수정본은 NOT_READY를 반환해야 합니다.
     */
    @Test
    void activePeriodExpiredWhileWaitingForRowLock() {
        Fixture f = fixtureWithExistingActivePeriod();

        // 두 단계 조회 중 배타 잠금이 끝나는 순간 경계가 바뀝니다.
        QuestPeriodAssignmentService.Result result = f.service().assign(WINDOW);

        assertEquals(QuestPeriodAssignmentService.Outcome.NOT_READY, result.outcome());
        verify(f.assignments(), never()).countByQuestPeriodId(anyLong());
        verifyNoInteractions(f.selector());
    }

    /**
     * FAILED를 기록하는 별도 트랜잭션도 같은 잠금 경계를 통과할 수 있습니다.
     * 기존 ACTIVE 기간에 대한 잘못된 성공 반환을 방지합니다.
     * 만료된 데이터에 FAILURE 기록을 소급 추가하지 않아야 합니다.
     */
    @Test
    void failureRecordExpiredWhileWaitingForRowLock() {
        Fixture f = fixtureWithExistingActivePeriod();

        // 실패 기록용 트랜잭션의 조기 반환도 새 시계를 읽어 판정합니다.
        QuestPeriodAssignmentService.Result result = f.service().recordSelectionFailure(
                WINDOW, "INSUFFICIENT_ACTIVE_TEMPLATES", "LOW 부족");

        assertEquals(QuestPeriodAssignmentService.Outcome.NOT_READY, result.outcome());
        verify(f.periods(), never()).save(any(QuestPeriod.class));
        verifyNoInteractions(f.assignments());
    }

    /**
     * 정상 기간·ACTIVE 행을 반환하되 배타 잠금 응답 시 Clock을 자정으로 이동합니다.
     * 기존 기간이 있으므로 부재 키 Gap Lock은 테스트하지 않습니다.
     * 시계 전환 외의 업무 값은 정상 상태로 유지합니다.
     */
    private Fixture fixtureWithExistingActivePeriod() {
        MutableClock clock = new MutableClock(NOW);
        SeasonQueryService seasonQuery = mock(SeasonQueryService.class);
        SeasonRepository seasonRepository = mock(SeasonRepository.class);
        QuestPeriodCalendar calendar = mock(QuestPeriodCalendar.class);
        QuestTemplateSelectionService selector = mock(QuestTemplateSelectionService.class);
        QuestPeriodRepository periods = mock(QuestPeriodRepository.class);
        QuestAssignmentRepository assignments = mock(QuestAssignmentRepository.class);
        QuestTemplateRepository templates = mock(QuestTemplateRepository.class);
        QuestTemplateVersionRepository versions = mock(QuestTemplateVersionRepository.class);
        Season season = mock(Season.class);
        QuestPeriod active = mock(QuestPeriod.class);

        // 시즌 공유 잠금은 자정 이전에 성공하도록 설정합니다.
        when(seasonQuery.findCurrentSeasonForShareIfPresent()).thenReturn(Optional.of(season));
        when(season.getStartAt()).thenReturn(FIRST_START);
        when(season.getEndAt()).thenReturn(FIRST_START.plus(Duration.ofDays(28)));
        when(seasonRepository.findFirstByOrderByStartAtAsc()).thenReturn(Optional.of(season));
        when(calendar.currentWindow(FIRST_START, NOW, "DAILY")).thenReturn(Optional.of(WINDOW));

        // 부재 확인 뒤 실제 SELECT FOR UPDATE를 통과하는 순간 Clock이 경계를 넘습니다.
        when(periods.findByPeriodTypeAndStartAt("DAILY", WINDOW.startAt()))
                .thenReturn(Optional.of(active));
        when(periods.findForUpdateByPeriodTypeAndStartAt("DAILY", WINDOW.startAt()))
                .thenAnswer(invocation -> {
                    clock.set(EXPIRED);
                    return Optional.of(active);
                });
        when(active.getStatus()).thenReturn("ACTIVE");

        QuestPeriodPersistenceService service = new QuestPeriodPersistenceService(
                seasonQuery, seasonRepository, calendar, selector, periods, assignments,
                templates, versions, clock);
        return new Fixture(service, periods, assignments, selector);
    }

    /**
     * 테스트마다 필요한 Repository와 서비스 Mock을 함께 반환합니다.
     * 실제 DB를 쓰지 않고 Clock 주입으로 시간 경계를 결정적으로 재현합니다.
     * 다른 단위 테스트의 Mockito 상태와 Clock에 영향을 주지 않습니다.
     */
    private record Fixture(
            QuestPeriodPersistenceService service,
            QuestPeriodRepository periods,
            QuestAssignmentRepository assignments,
            QuestTemplateSelectionService selector
    ) {
    }

    /**
     * 원자적으로 Instant를 교체하는 테스트 전용 Clock입니다.
     * 서비스에서 Instant.now(clock)을 호출할 때마다 최신 값을 반환합니다.
     * OS와 DB의 현재 시각을 변경하지 않습니다.
     */
    private static final class MutableClock extends Clock {
        private final AtomicReference<Instant> now;

        private MutableClock(Instant initial) {
            now = new AtomicReference<>(initial);
        }

        private void set(Instant next) {
            now.set(next);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return Clock.fixed(instant(), zone);
        }

        @Override
        public Instant instant() {
            return now.get();
        }
    }
}
