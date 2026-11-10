package com.team.sharedcreature.quest.application;

import com.team.sharedcreature.quest.domain.QuestAssignment;
import com.team.sharedcreature.quest.domain.QuestPeriod;
import com.team.sharedcreature.quest.domain.QuestTemplate;
import com.team.sharedcreature.quest.domain.QuestTemplateVersion;
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
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * DB 공유 잠금 대기·템플릿 선정·Flush 도중 날짜가 바뀌는 상황을 모의합니다.
 * 만료된 기간에 새 Snapshot 또는 FAILED 이력을 추가하지 않는 경계를 검증합니다.
 * 실제 롤백 원자성은 REQUIRES_NEW가 적용되는 MySQL 통합 테스트의 책임입니다.
 */
class QuestPeriodBoundaryTest {

    private static final Instant FIRST_START = Instant.parse("2026-11-01T15:00:00Z");
    private static final Instant NOW = Instant.parse("2026-11-09T18:00:00Z");
    private static final Instant EXPIRED = Instant.parse("2026-11-10T15:00:00Z");
    private static final QuestPeriodCalendar.PeriodWindow WINDOW =
            new QuestPeriodCalendar.PeriodWindow(
                    "DAILY", LocalDate.of(2026, 11, 10),
                    Instant.parse("2026-11-09T15:00:00Z"),
                    EXPIRED, 8L);

    /**
     * 잠금을 기다리는 동안 자정이 지나면 기간 생성에 진입하지 않습니다.
     * 잠금 대기 전 시각으로 검사하는 기존 구현이라면 이 검증은 실패합니다.
     * 기간 SELECT 및 템플릿 조회를 수행하지 않는지도 확인합니다.
     */
    @Test
    void expiredDuringSeasonShareLockSkipsAssignment() {
        Fixture f = fixture();
        when(f.seasonQuery().findCurrentSeasonForShareIfPresent()).thenAnswer(invocation -> {
            // 공유 잠금 획득이 완료되기 전 시간이 다음 KST 자정으로 이동했습니다.
            f.clock().set(EXPIRED);
            return Optional.of(f.season());
        });

        QuestPeriodAssignmentService.Result result = f.persistence().assign(WINDOW);

        assertEquals(QuestPeriodAssignmentService.Outcome.NOT_READY, result.outcome());
        verifyNoInteractions(f.periods(), f.assignments(), f.selector());
    }

    /**
     * 선정 실패를 기록하기 전에도 실제 공유 잠금 후 시각을 사용합니다.
     * 과거 날짜를 새 FAILED 기간으로 소급 저장하는 것을 방지합니다.
     * INSERT와 UPDATE 모두 실행되지 않아야 합니다.
     */
    @Test
    void expiredDuringSeasonShareLockSkipsFailureRecord() {
        Fixture f = fixture();
        when(f.seasonQuery().findCurrentSeasonForShareIfPresent()).thenAnswer(invocation -> {
            f.clock().set(EXPIRED);
            return Optional.of(f.season());
        });

        QuestPeriodAssignmentService.Result result = f.persistence().recordSelectionFailure(
                WINDOW, "INSUFFICIENT_ACTIVE_TEMPLATES", "LOW 부족");

        assertEquals(QuestPeriodAssignmentService.Outcome.NOT_READY, result.outcome());
        verifyNoInteractions(f.periods(), f.assignments(), f.selector());
    }

    /**
     * 템플릿 목록을 읽고 선정하는 동안 자정이 지났을 때 저장을 시작하지 않습니다.
     * 후보는 정상 3개이므로 만료 판정만으로 건너뛰어야 합니다.
     * 일부 배정이 저장되는 상황도 허용하지 않습니다.
     */
    @Test
    void expiredDuringTemplateSelectionDoesNotInsert() {
        Fixture f = fixture();
        when(f.selector().select(WINDOW)).thenAnswer(invocation -> {
            f.clock().set(EXPIRED);
            return dailyBatch();
        });

        QuestPeriodAssignmentService.Result result = f.persistence().assign(WINDOW);

        assertEquals(QuestPeriodAssignmentService.Outcome.NOT_READY, result.outcome());
        verify(f.periods(), never()).saveAndFlush(any(QuestPeriod.class));
        verify(f.assignments(), never()).save(any(QuestAssignment.class));
    }

    /**
     * Snapshot이 Flush된 직후 자정이 지나면 반환이 아닌 예외로 트랜잭션을 롤백합니다.
     * 테스트에서는 Repository를 Mock으로 대체하므로 실제 DB Rollback은 검증하지 않습니다.
     * 예외가 정상적으로 전파돼 트랜잭션 인터셉터에 도달하는지만 확인합니다.
     */
    @Test
    void expiredAfterAssignmentFlushRequestsRollback() {
        Fixture f = fixture();
        when(f.selector().select(WINDOW)).thenReturn(dailyBatch());
        when(f.periods().saveAndFlush(any(QuestPeriod.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        when(f.templates().getReferenceById(anyLong())).thenReturn(mock(QuestTemplate.class));
        when(f.versions().getReferenceById(anyLong())).thenReturn(mock(QuestTemplateVersion.class));
        doAnswer(invocation -> {
            // 마지막 SQL Flush 직후에 날짜 경계를 넘도록 고정 시계를 이동합니다.
            f.clock().set(EXPIRED);
            return null;
        }).when(f.assignments()).flush();

        assertThrows(QuestPeriodPersistenceService.WindowExpiredDuringPersistenceException.class,
                () -> f.persistence().assign(WINDOW));
        verify(f.assignments(), times(3)).save(any(QuestAssignment.class));
        verify(f.periods()).flush();
    }

    /**
     * FAILED 기간 저장 도중 만료되면 해당 실패 행도 Rollback해야 합니다.
     * 이미 존재한 FAILED 행 업데이트와 신규 INSERT 모두 같은 검사 대상입니다.
     * 최종 Flush 후 예외가 발생하는지를 검증합니다.
     */
    @Test
    void expiredAfterFailureRecordFlushRequestsRollback() {
        Fixture f = fixture();
        when(f.periods().save(any(QuestPeriod.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        doAnswer(invocation -> {
            f.clock().set(EXPIRED);
            return null;
        }).when(f.periods()).flush();

        assertThrows(QuestPeriodPersistenceService.WindowExpiredDuringPersistenceException.class,
                () -> f.persistence().recordSelectionFailure(
                        WINDOW, "INSUFFICIENT_ACTIVE_TEMPLATES", "LOW 부족"));
        verify(f.periods()).save(any(QuestPeriod.class));
    }

    /**
     * 독립 영속화 트랜잭션의 만료 롤백 이후 외부 조정자는 NOT_READY를 반환합니다.
     * 실제 업무 실패와 구분하도록 기간 만료 전용 예외만 변환합니다.
     * 별도 실패 행 기록은 시도하지 않아야 합니다.
     */
    @Test
    void coordinatorTranslatesExpiredRollbackToNotReady() {
        QuestPeriodPersistenceService persistence = mock(QuestPeriodPersistenceService.class);
        when(persistence.assign(WINDOW)).thenThrow(
                new QuestPeriodPersistenceService.WindowExpiredDuringPersistenceException(
                        WINDOW.periodType(), WINDOW.startAt()));
        QuestPeriodAssignmentService service = new QuestPeriodAssignmentService(persistence);

        QuestPeriodAssignmentService.Result result = service.assign(WINDOW);

        assertEquals(QuestPeriodAssignmentService.Outcome.NOT_READY, result.outcome());
        verify(persistence, never()).recordSelectionFailure(any(), anyString(), anyString());
    }

    /**
     * 실패 이력 기록 중 날짜 경계가 바뀌어도 만료 기간으로 돌려줍니다.
     * 원래 SelectionException과 만료 롤백 예외를 구별해 처리합니다.
     * 두 번째 실패 기록이나 신규 Snapshot 생성은 없어야 합니다.
     */
    @Test
    void coordinatorTranslatesExpiredFailureRecordToNotReady() {
        QuestPeriodPersistenceService persistence = mock(QuestPeriodPersistenceService.class);
        when(persistence.assign(WINDOW)).thenThrow(
                new QuestTemplateSelectionService.SelectionException(
                        QuestTemplateSelectionService.FailureCode.INSUFFICIENT_ACTIVE_TEMPLATES,
                        "LOW 부족"));
        when(persistence.recordSelectionFailure(WINDOW,
                "INSUFFICIENT_ACTIVE_TEMPLATES", "LOW 부족")).thenThrow(
                new QuestPeriodPersistenceService.WindowExpiredDuringPersistenceException(
                        WINDOW.periodType(), WINDOW.startAt()));
        QuestPeriodAssignmentService service = new QuestPeriodAssignmentService(persistence);

        QuestPeriodAssignmentService.Result result = service.assign(WINDOW);

        assertEquals(QuestPeriodAssignmentService.Outcome.NOT_READY, result.outcome());
        verify(persistence, times(1)).recordSelectionFailure(
                WINDOW, "INSUFFICIENT_ACTIVE_TEMPLATES", "LOW 부족");
    }

    /**
     * 템플릿 내용은 이 테스트의 검증 대상이 아니므로 최소 슬롯만 구성합니다.
     * 실제 엔티티 캡처에 필요한 필수 필드를 포함합니다.
     * 예상 슬롯은 DAILY 규칙에 맞춰 총 3개입니다.
     */
    private QuestTemplateSelectionService.SelectionBatch dailyBatch() {
        var slots = IntStream.rangeClosed(1, 3)
                .mapToObj(slot -> new QuestTemplateSelectionService.SelectedQuest(
                        slot, (long) slot, (long) slot + 10,
                        "D-TEST-" + slot,
                        slot == 1 ? "LOW" : slot == 2 ? "MEDIUM" : "HIGH",
                        "제목" + slot, "설명", "SELF_CHECK", "{}", 100L, 10L))
                .toList();
        return new QuestTemplateSelectionService.SelectionBatch("DAILY", 8L, slots);
    }

    /**
     * Repository 및 Clock을 한 세트로 준비하여 경계별 시각 변경을 재현합니다.
     * 실제 DB 잠금은 사용하지 않고 시즌 공유 잠금 획득 시점을 Mock으로 제어합니다.
     * 기존 서비스 생성자 계약을 그대로 사용하므로 다른 Bean 변경은 필요하지 않습니다.
     */
    private Fixture fixture() {
        MutableClock clock = new MutableClock(NOW, ZoneOffset.UTC);
        SeasonQueryService seasonQuery = mock(SeasonQueryService.class);
        SeasonRepository seasons = mock(SeasonRepository.class);
        QuestPeriodCalendar calendar = mock(QuestPeriodCalendar.class);
        QuestTemplateSelectionService selector = mock(QuestTemplateSelectionService.class);
        QuestPeriodRepository periods = mock(QuestPeriodRepository.class);
        QuestAssignmentRepository assignments = mock(QuestAssignmentRepository.class);
        QuestTemplateRepository templates = mock(QuestTemplateRepository.class);
        QuestTemplateVersionRepository versions = mock(QuestTemplateVersionRepository.class);
        Season season = mock(Season.class);

        // 유효한 시즌과 현재 KST 일일 기간을 준비한 뒤 테스트별로 Clock을 이동합니다.
        when(seasonQuery.findCurrentSeasonForShareIfPresent()).thenReturn(Optional.of(season));
        when(season.getStartAt()).thenReturn(FIRST_START);
        when(season.getEndAt()).thenReturn(FIRST_START.plus(Duration.ofDays(28)));
        when(seasons.findFirstByOrderByStartAtAsc()).thenReturn(Optional.of(season));
        when(calendar.currentWindow(FIRST_START, NOW, "DAILY")).thenReturn(Optional.of(WINDOW));

        QuestPeriodPersistenceService persistence = new QuestPeriodPersistenceService(
                seasonQuery, seasons, calendar, selector, periods,
                assignments, templates, versions, clock);
        return new Fixture(persistence, clock, seasonQuery, season,
                periods, assignments, templates, versions, selector);
    }

    /**
     * 단위 테스트에서 사용하는 의존성과 변경 가능한 Clock을 그룹화합니다.
     * 별도 Spring 컨텍스트 없이 기존 생성자를 직접 호출합니다.
     * 각 테스트는 독립 Fixture를 갖기 때문에 다른 테스트에 영향을 주지 않습니다.
     */
    private record Fixture(
            QuestPeriodPersistenceService persistence,
            MutableClock clock,
            SeasonQueryService seasonQuery,
            Season season,
            QuestPeriodRepository periods,
            QuestAssignmentRepository assignments,
            QuestTemplateRepository templates,
            QuestTemplateVersionRepository versions,
            QuestTemplateSelectionService selector) {
    }

    /**
     * 테스트 도중 UTC Instant를 임의로 변경할 수 있는 Clock 구현입니다.
     * 생산 코드는 Clock 인터페이스만 참조하므로 시간 경합을 결정적으로 재현합니다.
     * Thread-safe 값 하나를 공유해 조회 시점을 순서대로 바꿉니다.
     */
    private static final class MutableClock extends Clock {
        private final AtomicReference<Instant> current;
        private final ZoneId zone;

        private MutableClock(Instant initial, ZoneId zone) {
            this.current = new AtomicReference<>(initial);
            this.zone = zone;
        }

        private void set(Instant next) {
            current.set(next);
        }

        @Override
        public ZoneId getZone() {
            return zone;
        }

        @Override
        public Clock withZone(ZoneId nextZone) {
            return new MutableClock(current.get(), nextZone);
        }

        @Override
        public Instant instant() {
            return current.get();
        }
    }
}
