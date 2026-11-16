
package com.team.sharedcreature._core.time;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.assertEquals;

class KstPeriodCalculatorTest {

    private final KstPeriodCalculator calculator =
            new KstPeriodCalculator();

    /**
     * 일일 기간의 시작과 종료 시각을 검증합니다.
     * KST 자정을 UTC 15시로 변환해야 합니다.
     * 서버 타임존 설정과 관계없이 같은 결과여야 합니다.
     */
    @Test
    void dailyPeriodUsesKstMidnight() {
        var window = calculator.dailyAt(
                Instant.parse("2026-11-06T03:00:00Z")
        );

        // 11월 6일 KST 하루를 UTC로 표현합니다.
        assertEquals(LocalDate.of(2026, 11, 6),
                window.periodDate());
        assertEquals(Instant.parse("2026-11-05T15:00:00Z"),
                window.startAt());
        assertEquals(Instant.parse("2026-11-06T15:00:00Z"),
                window.endAt());
    }

    /**
     * KST 자정 직전과 직후의 기간을 비교합니다.
     * 자정 직전은 이전 날짜에 속합니다.
     * 정확히 자정이면 새 날짜에 속해야 합니다.
     */
    @Test
    void dailyPeriodChangesExactlyAtMidnight() {
        var before = calculator.dailyAt(
                Instant.parse("2026-11-05T14:59:59Z")
        );
        var after = calculator.dailyAt(
                Instant.parse("2026-11-05T15:00:00Z")
        );

        // UTC 15시가 KST 자정입니다.
        assertEquals(LocalDate.of(2026, 11, 5),
                before.periodDate());
        assertEquals(LocalDate.of(2026, 11, 6),
                after.periodDate());
    }

    /**
     * 주간 기간이 월요일에 시작하는지 검증합니다.
     * 2026년 11월 2일은 월요일입니다.
     * 종료는 다음 월요일 자정으로 계산해야 합니다.
     */
    @Test
    void weeklyPeriodStartsOnMonday() {
        var window = calculator.weeklyAt(
                Instant.parse("2026-11-06T03:00:00Z")
        );

        // 11월 2일 ~ 11월 9일 KST
        assertEquals(LocalDate.of(2026, 11, 2),
                window.periodDate());
        assertEquals(Instant.parse("2026-11-01T15:00:00Z"),
                window.startAt());
        assertEquals(Instant.parse("2026-11-08T15:00:00Z"),
                window.endAt());
    }

    /**
     * 일요일과 월요일 사이의 주간 경계를 검증합니다.
     * 일요일 마지막 순간은 이전 주입니다.
     * 월요일 자정부터 새 주간 기간이 시작됩니다.
     */
    @Test
    void weeklyPeriodChangesOnMondayMidnight() {
        var before = calculator.weeklyAt(
                Instant.parse("2026-11-01T14:59:59Z")
        );
        var after = calculator.weeklyAt(
                Instant.parse("2026-11-01T15:00:00Z")
        );

        // 한국 시간으로 일요일 23:59:59와 월요일 00:00
        assertEquals(LocalDate.of(2026, 10, 26),
                before.periodDate());
        assertEquals(LocalDate.of(2026, 11, 2),
                after.periodDate());
    }
}
