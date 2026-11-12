
package com.team.sharedcreature._core.time;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;

class SeasonCalendarTest {

    private final SeasonCalendar calendar = new SeasonCalendar();

    private static final Instant FIRST_START =
            Instant.parse("2026-11-01T15:00:00Z");

    /**
     * 첫 시즌 중간 시각의 구간 계산을 확인합니다.
     * 첫 번째 구간 번호는 0이어야 합니다.
     * 종료는 시작 시각에서 4주 뒤입니다.
     */
    @Test
    void firstSeasonWindow() {
        var window = calendar.currentWindow(
                FIRST_START,
                Instant.parse("2026-11-06T03:00:00Z")
        ).orElseThrow();

        // 첫 시즌: 11월 2일 ~ 11월 30일 KST
        assertEquals(0L, window.index());
        assertEquals(FIRST_START, window.startAt());
        assertEquals(
                Instant.parse("2026-11-29T15:00:00Z"),
                window.endAt()
        );
    }

    /**
     * 첫 시즌 종료 직전의 경계를 확인합니다.
     * 종료 시각 전에는 첫 번째 구간에 속합니다.
     * 날짜가 바뀌기 직전에도 다음 구간으로 넘어가지 않습니다.
     */
    @Test
    void justBeforeNextSeason() {
        var window = calendar.currentWindow(
                FIRST_START,
                Instant.parse("2026-11-29T14:59:59Z")
        ).orElseThrow();

        // 다음 시즌 시작 1초 전이므로 0번 구간입니다.
        assertEquals(0L, window.index());
    }

    /**
     * 정확히 다음 시즌 시작 시각의 경계를 확인합니다.
     * 4주가 경과하면 새 구간으로 넘어갑니다.
     * 경계 시각은 새로운 구간의 시작에 포함됩니다.
     */
    @Test
    void exactlyAtNextSeason() {
        var window = calendar.currentWindow(
                FIRST_START,
                Instant.parse("2026-11-29T15:00:00Z")
        ).orElseThrow();

        // 두 번째 4주 구간으로 전환합니다.
        assertEquals(1L, window.index());
        assertEquals(
                Instant.parse("2026-11-29T15:00:00Z"),
                window.startAt()
        );
        assertEquals(
                Instant.parse("2026-12-27T15:00:00Z"),
                window.endAt()
        );
    }

    /**
     * 장기간 서버 중단 후 현재 구간을 계산합니다.
     * 지나간 중간 시즌을 순서대로 생성하지 않습니다.
     * 실제 DB 시즌 번호와 관계없이 달력 위치만 반환합니다.
     */
    @Test
    void skipsElapsedCalendarWindows() {
        var window = calendar.currentWindow(
                FIRST_START,
                Instant.parse("2027-02-01T03:00:00Z")
        ).orElseThrow();

        // 2027년 1월 25일부터 시작하는 3번 구간입니다.
        assertEquals(3L, window.index());
        assertEquals(
                Instant.parse("2027-01-24T15:00:00Z"),
                window.startAt()
        );
        assertEquals(
                Instant.parse("2027-02-21T15:00:00Z"),
                window.endAt()
        );
    }

    /**
     * 첫 시즌 시작 전에는 시즌 구간이 없음을 확인합니다.
     * 아직 시작되지 않은 시즌을 자동 생성하지 않습니다.
     * DB 초기화 정책과 분리된 달력 계산을 검증합니다.
     */
    @Test
    void beforeFirstSeasonReturnsEmpty() {
        var result = calendar.currentWindow(
                FIRST_START,
                Instant.parse("2026-11-01T14:59:59Z")
        );

        // 시작 전에는 현재 구간이 존재하지 않습니다.
        assertTrue(result.isEmpty());
    }

    /**
     * 첫 시즌 시작 기준의 유효성을 검증합니다.
     * 최초 시작은 KST 월요일 자정이어야 합니다.
     * 잘못된 시드 데이터는 조용히 보정하지 않습니다.
     */
    @Test
    void invalidFirstSeasonStartIsRejected() {
        Instant invalidStart =
                Instant.parse("2026-11-01T14:00:00Z");

        // KST 일요일 23:00은 잘못된 기준 시각입니다.
        assertThrows(IllegalArgumentException.class, () ->
                calendar.currentWindow(
                        invalidStart,
                        Instant.parse("2026-11-06T03:00:00Z")
                )
        );
    }
}
