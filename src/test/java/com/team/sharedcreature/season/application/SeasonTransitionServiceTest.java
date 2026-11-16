
package com.team.sharedcreature.season.application;

import com.team.sharedcreature.season.domain.Season;
import com.team.sharedcreature.season.infrastructure.SeasonRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.BeanUtils;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 시즌 종료 진입 Service의 상태 변경 규칙을 검증합니다.
 * Repository의 배타 잠금 조회 호출과 상태 변경 결과를 확인합니다.
 * 실제 DB의 잠금 및 Commit 검증은 통합 테스트에서 수행합니다.
 */
@ExtendWith(MockitoExtension.class)
class SeasonTransitionServiceTest {

    private static final Long SEASON_ID = 99L;

    private static final Instant START =
            Instant.parse("2026-11-01T15:00:00Z");

    private static final Instant END =
            Instant.parse("2026-11-29T15:00:00Z");

    private static final Instant CREATED =
            START.minusSeconds(3600);

    @Mock
    private SeasonRepository seasonRepository;

    private MutableClock clock;
    private SeasonTransitionService service;

    /**
     * 테스트마다 새로운 고정 시각과 Service를 생성합니다.
     * 기본 테스트 시각은 시즌 종료 경계와 일치합니다.
     * 각 테스트는 독립된 시즌 엔티티를 사용합니다.
     */
    @BeforeEach
    void setUp() {
        clock = new MutableClock(END);
        service = new SeasonTransitionService(
                seasonRepository,
                clock
        );
    }

    /**
     * 만료된 ACTIVE 시즌의 정상 종료 진입을 검증합니다.
     * 배타 잠금 조회로 얻은 엔티티를 직접 변경합니다.
     * 상태와 수정 시각을 모두 확인합니다.
     */
    @Test
    void expiredActiveSeasonBeginsClosing() {
        Season season = prepareSeason("ACTIVE");

        // 종료 경계에서 CLOSING으로 전환합니다.
        boolean changed = service.beginClosingIfExpired(SEASON_ID);

        assertTrue(changed);
        assertEquals("CLOSING", season.getStatus());
        assertEquals(END, season.getUpdatedAt());

        verify(seasonRepository).findForUpdateById(SEASON_ID);
    }

    /**
     * 초기화 중 만료된 SCHEDULED 시즌을 종료합니다.
     * ACTIVE 상태로 전환하지 않고 바로 CLOSING에 진입합니다.
     * 만료된 시즌을 소급 활성화하지 않는 규칙입니다.
     */
    @Test
    void expiredScheduledSeasonBeginsClosing() {
        Season season = prepareSeason("SCHEDULED");

        assertTrue(service.beginClosingIfExpired(SEASON_ID));

        assertEquals("CLOSING", season.getStatus());
    }

    /**
     * 종료 전 ACTIVE 시즌은 상태를 변경하지 않습니다.
     * 종료 경계 직전의 시각으로 호출합니다.
     * 수정 시각도 그대로 보존해야 합니다.
     */
    @Test
    void activeSeasonBeforeEndIsNotClosed() {
        Season season = prepareSeason("ACTIVE");
        clock.set(END.minusSeconds(1));

        assertFalse(service.beginClosingIfExpired(SEASON_ID));

        assertEquals("ACTIVE", season.getStatus());
        assertEquals(CREATED, season.getUpdatedAt());
    }

    /**
     * 종료 전 SCHEDULED 시즌은 초기화를 계속할 수 있어야 합니다.
     * 종료 시각 이전에는 CLOSING으로 전환하지 않습니다.
     * 실제 그룹 초기화 작업은 이 테스트의 범위 밖입니다.
     */
    @Test
    void scheduledSeasonBeforeEndIsNotClosed() {
        Season season = prepareSeason("SCHEDULED");
        clock.set(END.minusSeconds(1));

        assertFalse(service.beginClosingIfExpired(SEASON_ID));

        assertEquals("SCHEDULED", season.getStatus());
        assertEquals(CREATED, season.getUpdatedAt());
    }

    /**
     * CLOSING 시즌을 다시 변경하지 않는지 확인합니다.
     * 다른 실행자가 종료를 시작했더라도 상태를 덮어쓰지 않습니다.
     * 후속 Snapshot 복구는 별도의 작업으로 처리합니다.
     */
    @Test
    void closingSeasonIsNotChangedAgain() {
        Season season = prepareSeason("CLOSING");

        assertFalse(service.beginClosingIfExpired(SEASON_ID));

        assertEquals("CLOSING", season.getStatus());
        assertEquals(CREATED, season.getUpdatedAt());
    }

    /**
     * ENDED 시즌의 중복 종료 요청을 무시합니다.
     * 최종 종료 상태와 수정 시각을 보존합니다.
     * 이미 완료된 시즌을 다시 종료 대상으로 만들지 않습니다.
     */
    @Test
    void endedSeasonIsNotChangedAgain() {
        Season season = prepareSeason("ENDED");

        assertFalse(service.beginClosingIfExpired(SEASON_ID));

        assertEquals("ENDED", season.getStatus());
        assertEquals(CREATED, season.getUpdatedAt());
    }

    /**
     * DB 잠금 대기 중 종료 경계를 넘는 상황을 검증합니다.
     * Repository가 시즌을 반환하기 직전에 Clock을 변경합니다.
     * 서비스는 잠금 이후에 읽은 시각으로 종료를 판단해야 합니다.
     */
    @Test
    void crossingEndWhileWaitingForLockBeginsClosing() {
        Season season = prepareSeason("ACTIVE");
        clock.set(END.minusSeconds(1));

        // 배타 잠금 조회가 반환될 때 종료 경계에 도달합니다.
        when(seasonRepository.findForUpdateById(SEASON_ID))
                .thenAnswer(invocation -> {
                    clock.set(END);
                    return Optional.of(season);
                });

        assertTrue(service.beginClosingIfExpired(SEASON_ID));

        assertEquals("CLOSING", season.getStatus());
        assertEquals(END, season.getUpdatedAt());
    }

    /**
     * 대상 시즌이 존재하지 않으면 예외를 반환합니다.
     * 없는 시즌을 정상 종료로 취급해서는 안 됩니다.
     * Repository 결과가 빈 경우를 검증합니다.
     */
    @Test
    void missingSeasonIsRejected() {
        when(seasonRepository.findForUpdateById(SEASON_ID))
                .thenReturn(Optional.empty());

        assertThrows(IllegalStateException.class, () ->
                service.beginClosingIfExpired(SEASON_ID)
        );
    }

    /**
     * 테스트에 사용할 시즌 엔티티를 준비합니다.
     * DB 없이 실제 Season의 상태 전이 메서드를 검증합니다.
     * Repository는 조회한 엔티티를 반환하도록 설정합니다.
     *
     * @param status 초기 시즌 상태
     * @return 테스트에서 사용할 시즌
     */
    private Season prepareSeason(String status) {
        Season season = BeanUtils.instantiateClass(Season.class);

        // JPA로 복원한 시즌 엔티티의 상태를 재현합니다.
        ReflectionTestUtils.setField(season, "id", SEASON_ID);
        ReflectionTestUtils.setField(season, "status", status);
        ReflectionTestUtils.setField(season, "startAt", START);
        ReflectionTestUtils.setField(season, "endAt", END);
        ReflectionTestUtils.setField(season, "createdAt", CREATED);
        ReflectionTestUtils.setField(season, "updatedAt", CREATED);

        when(seasonRepository.findForUpdateById(SEASON_ID))
                .thenReturn(Optional.of(season));

        return season;
    }

    /**
     * 테스트 도중 현재 시각을 변경할 수 있는 Clock입니다.
     * DB 잠금 대기 중 시각이 흐르는 상황을 재현합니다.
     * 테스트마다 독립적인 인스턴스를 사용합니다.
     */
    private static class MutableClock extends Clock {

        private Instant instant;

        private MutableClock(Instant instant) {
            this.instant = instant;
        }

        /**
         * 테스트의 현재 시각을 변경합니다.
         * 실제 대기나 Thread.sleep 없이 경계를 재현합니다.
         * 지정한 시각을 이후 Clock 호출에서 반환합니다.
         */
        private void set(Instant instant) {
            this.instant = instant;
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
            return instant;
        }
    }
}
