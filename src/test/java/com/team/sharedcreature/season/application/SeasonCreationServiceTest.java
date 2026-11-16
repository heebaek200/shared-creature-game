package com.team.sharedcreature.season.application;

import com.team.sharedcreature._core.time.SeasonCalendar;
import com.team.sharedcreature.season.domain.Season;
import com.team.sharedcreature.season.infrastructure.SeasonRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.BeanUtils;
import org.springframework.data.domain.Pageable;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * 새로운 SCHEDULED 시즌을 만드는 업무 규칙을 DB 없이 검증합니다.
 * 최초 시즌 잠금, 현재 4주 구간, 중간 구간 생략 및 상태별 결과를 확인합니다.
 * 실제 MySQL 트랜잭션과 동시 실행은 별도의 통합 테스트가 검증합니다.
 */
@ExtendWith(MockitoExtension.class)
class SeasonCreationServiceTest {

    private static final Instant FIRST_START = Instant.parse("2026-11-01T15:00:00Z");
    private static final Instant FIRST_END = Instant.parse("2026-11-29T15:00:00Z");
    private static final Instant SECOND_START = FIRST_END;
    private static final Instant SECOND_END = Instant.parse("2026-12-27T15:00:00Z");

    @Mock
    private SeasonRepository repository;

    private MutableClock clock;
    private SeasonCreationService service;

    /**
     * 테스트별로 독립된 Mock Repository와 시각을 구성합니다.
     * Calendar는 실제 28일 계산기를 사용합니다.
     * 기본 시각은 두 번째 시즌 구간의 시작 직후입니다.
     */
    @BeforeEach
    void setUp() {
        clock = new MutableClock(Instant.parse("2026-12-01T00:00:00Z"));
        service = new SeasonCreationService(repository, new SeasonCalendar(), clock);
    }

    /**
     * 첫 시즌 종료 후 현재 구간 하나를 SCHEDULED로 생성합니다.
     * 저장할 시즌 번호는 최신 시즌 번호에 1을 더합니다.
     * 생성 시각은 현재이며 시즌 달력 시작 시각과 다를 수 있습니다.
     */
    @Test
    void createsSecondSeasonAfterPreviousEnded() {
        arrange(first("ENDED"), first("ENDED"));
        willPersistWithId(22L);

        SeasonCreationService.CreationResult result = service.createCurrentScheduledIfMissing();

        assertEquals(SeasonCreationService.Outcome.CREATED, result.outcome());
        assertEquals(22L, result.seasonId());
        assertEquals(SECOND_START, result.windowStartAt());
        ArgumentCaptor<Season> captor = ArgumentCaptor.forClass(Season.class);
        verify(repository).saveAndFlush(captor.capture());
        Season created = captor.getValue();
        assertEquals(2, created.getSeasonNumber());
        assertEquals("SCHEDULED", created.getStatus());
        assertEquals(SECOND_START, created.getStartAt());
        assertEquals(SECOND_END, created.getEndAt());
        assertEquals(clock.instant(), created.getCreatedAt());
    }

    /**
     * 여러 번의 4주 구간을 건너뛰더라도 현재 구간만 생성합니다.
     * 달력 인덱스 3이어도 시즌 번호는 마지막 기록 번호 + 1입니다.
     * 중간에 지나간 시즌을 반복 생성하지 않습니다.
     */
    @Test
    void skipsMultipleExpiredCalendarWindows() {
        clock.set(Instant.parse("2027-02-01T03:00:00Z"));
        arrange(first("ENDED"), first("ENDED"));
        willPersistWithId(23L);

        assertEquals(SeasonCreationService.Outcome.CREATED,
                service.createCurrentScheduledIfMissing().outcome());

        ArgumentCaptor<Season> captor = ArgumentCaptor.forClass(Season.class);
        verify(repository, times(1)).saveAndFlush(captor.capture());
        assertEquals(2, captor.getValue().getSeasonNumber());
        assertEquals(Instant.parse("2027-01-24T15:00:00Z"), captor.getValue().getStartAt());
        assertEquals(Instant.parse("2027-02-21T15:00:00Z"), captor.getValue().getEndAt());
    }

    /**
     * 이전 시즌이 아직 CLOSING이면 새로운 시즌을 만들지 않습니다.
     * 종료 Snapshot과 ENDED Commit이 먼저 필요합니다.
     * WAITING_FOR_CLOSURE는 후속 정상화에서 다시 시도할 상태입니다.
     */
    @Test
    void waitsForUnfinishedClosingSeason() {
        arrange(first("CLOSING"), first("CLOSING"));

        var result = service.createCurrentScheduledIfMissing();

        assertEquals(SeasonCreationService.Outcome.WAITING_FOR_CLOSURE, result.outcome());
        verify(repository, never()).saveAndFlush(any());
    }

    /**
     * 직전 시즌이 만료돼도 여전히 ACTIVE이면 생성을 보류합니다.
     * 시즌 종료의 배타 잠금과 상태 전이가 먼저 실행돼야 합니다.
     * 중복 시즌 생성으로 종료 절차를 우회하지 않습니다.
     */
    @Test
    void waitsForExpiredActiveSeason() {
        arrange(first("ACTIVE"), first("ACTIVE"));

        assertEquals(SeasonCreationService.Outcome.WAITING_FOR_CLOSURE,
                service.createCurrentScheduledIfMissing().outcome());
        verify(repository, never()).saveAndFlush(any());
    }

    /**
     * 이미 저장한 현재 시즌은 상태와 무관하게 다시 생성하지 않습니다.
     * 이 테스트는 최초 시즌과 현재 SCHEDULED 시즌이 공존하는 경우입니다.
     * 그룹 초기화는 다음 단계에서 기존 시즌을 이어서 사용합니다.
     */
    @Test
    void existingCurrentScheduledSeasonIsReused() {
        Season current = make(2L, 2, SECOND_START, SECOND_END, "SCHEDULED");
        arrange(first("ENDED"), current);

        var result = service.createCurrentScheduledIfMissing();

        assertEquals(SeasonCreationService.Outcome.ALREADY_EXISTS, result.outcome());
        assertEquals(2L, result.seasonId());
        verify(repository, never()).saveAndFlush(any());
    }

    /**
     * 서버가 첫 시즌 중간에 기동했다면 기존 첫 시즌을 반환합니다.
     * 최초 시즌보다 앞선 과거 시즌을 삽입해서는 안 됩니다.
     * 생성 대상이 없는 정상 상태로 처리합니다.
     */
    @Test
    void firstSeasonIsNotDuplicated() {
        clock.set(Instant.parse("2026-11-10T03:00:00Z"));
        arrange(first("ACTIVE"), first("ACTIVE"));

        assertEquals(SeasonCreationService.Outcome.ALREADY_EXISTS,
                service.createCurrentScheduledIfMissing().outcome());
        verify(repository, never()).saveAndFlush(any());
    }

    /**
     * 첫 시즌보다 이전 시각에는 자동으로 시즌을 만들지 않습니다.
     * 고정 시드의 시작 전에 애플리케이션이 기동한 상황입니다.
     * 최신 시즌 조회는 하지만 실제 INSERT는 하지 않습니다.
     */
    @Test
    void beforeFirstSeasonDoesNotCreate() {
        clock.set(FIRST_START.minusSeconds(1));
        arrange(first("SCHEDULED"), first("SCHEDULED"));

        assertEquals(SeasonCreationService.Outcome.NOT_STARTED,
                service.createCurrentScheduledIfMissing().outcome());
        verify(repository, never()).saveAndFlush(any());
    }

    /**
     * 최초 시드 행이 없으면 현재 시각으로 시즌을 추정하지 않습니다.
     * 운영 관리자가 문제를 인지할 수 있게 명시적인 예외를 발생시킵니다.
     * 후속 Repository 조회나 INSERT가 없어야 합니다.
     */
    @Test
    void missingFirstSeasonSeedIsRejected() {
        when(repository.lockFirstSeasonForCreation(any(Pageable.class)))
                .thenReturn(List.of());

        assertThrows(IllegalStateException.class,
                () -> service.createCurrentScheduledIfMissing());
        verify(repository, never()).findFirstByOrderByStartAtDesc();
        verify(repository, never()).saveAndFlush(any());
    }

    /**
     * 최초 행의 배타 잠금을 획득한 다음 시각을 확인해야 합니다.
     * 잠금 대기 중 정확히 다음 4주 구간으로 넘어간 상황을 재현합니다.
     * 시각을 잠금 이전에 계산했다면 현재 시즌을 놓치게 됩니다.
     */
    @Test
    void readsClockAfterFirstSeasonLock() {
        clock.set(FIRST_END.minusSeconds(1));
        Season first = first("ENDED");
        when(repository.lockFirstSeasonForCreation(any(Pageable.class)))
                .thenAnswer(invocation -> {
                    clock.set(FIRST_END);
                    return List.of(first);
                });
        when(repository.findFirstByOrderByStartAtDesc()).thenReturn(Optional.of(first));
        willPersistWithId(24L);

        assertEquals(SeasonCreationService.Outcome.CREATED,
                service.createCurrentScheduledIfMissing().outcome());

        InOrder order = inOrder(repository);
        order.verify(repository).lockFirstSeasonForCreation(any(Pageable.class));
        order.verify(repository).findFirstByOrderByStartAtDesc();
        order.verify(repository).saveAndFlush(any(Season.class));
    }

    /**
     * 저장된 최신 시즌 시작 시각이 4주 달력과 맞지 않으면 거절합니다.
     * 임의로 시즌을 만들어 비정상적인 기존 데이터를 덮지 않습니다.
     * 잘못된 시드·운영 데이터는 명시적으로 복구해야 합니다.
     */
    @Test
    void inconsistentLatestCalendarIsRejected() {
        Season broken = make(2L, 2, SECOND_START.plusSeconds(3600),
                SECOND_END.plusSeconds(3600), "ENDED");
        arrange(first("ENDED"), broken);

        assertThrows(IllegalStateException.class,
                () -> service.createCurrentScheduledIfMissing());
        verify(repository, never()).saveAndFlush(any());
    }

    /**
     * 조회 결과를 실제 JPA 엔티티 형태로 준비합니다.
     * 잠금된 첫 시즌과 가장 최근 시즌을 순서대로 반환합니다.
     * 호출자는 별도의 Snapshot 인스턴스를 제공할 수도 있습니다.
     */
    private void arrange(Season first, Season latest) {
        when(repository.lockFirstSeasonForCreation(any(Pageable.class)))
                .thenReturn(List.of(first));
        when(repository.findFirstByOrderByStartAtDesc())
                .thenReturn(Optional.of(latest));
    }

    /**
     * 신규 INSERT에 대한 가짜 자동 증가 ID를 설정합니다.
     * 저장 이전의 엔티티를 그대로 반환해 값 검증이 가능합니다.
     * 실제 데이터베이스 반영 여부는 통합 테스트에서 검증합니다.
     */
    private void willPersistWithId(Long id) {
        when(repository.saveAndFlush(any(Season.class)))
                .thenAnswer(invocation -> {
                    Season season = invocation.getArgument(0);
                    ReflectionTestUtils.setField(season, "id", id);
                    return season;
                });
    }

    /**
     * 최초 시즌 시드를 테스트에서 사용하는 데이터로 구성합니다.
     * 월요일 00시 KST에 시작하는 기존 시즌 번호 1입니다.
     * JPA protected 생성자는 Reflection으로 처리합니다.
     */
    private Season first(String status) {
        return make(1L, 1, FIRST_START, FIRST_END, status);
    }

    /**
     * JPA로 조회한 형태의 엔티티를 테스트 전용으로 조립합니다.
     * 실 서비스는 엔티티 생성 시 schedule() 팩토리를 사용합니다.
     * 테스트 코드에서는 protected 생성자에 직접 접근하지 않습니다.
     */
    private Season make(Long id, int number, Instant start, Instant end, String status) {
        Season season = BeanUtils.instantiateClass(Season.class);
        ReflectionTestUtils.setField(season, "id", id);
        ReflectionTestUtils.setField(season, "seasonNumber", number);
        ReflectionTestUtils.setField(season, "startAt", start);
        ReflectionTestUtils.setField(season, "endAt", end);
        ReflectionTestUtils.setField(season, "status", status);
        return season;
    }

    /**
     * 테스트에서 실제 시각을 명시적으로 변경할 수 있는 Clock입니다.
     * 잠금 대기 중 경계 시각이 바뀌는 상황을 재현합니다.
     * 테스트마다 새 인스턴스를 생성해 다른 테스트를 오염시키지 않습니다.
     */
    private static final class MutableClock extends Clock {
        private Instant now;

        private MutableClock(Instant now) {
            this.now = now;
        }

        /**
         * Clock이 반환할 현재 시각을 변경합니다.
         * 다른 스레드나 실제 시간 흐름에는 의존하지 않습니다.
         * 다음 instant() 호출부터 새로운 시각을 사용합니다.
         */
        private void set(Instant instant) {
            this.now = instant;
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
