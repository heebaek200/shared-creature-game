package com.team.sharedcreature.season.domain;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 신규 시즌 생성 팩토리의 상태와 유효 구간을 검사합니다.
 * 생성 시각과 달력 시작 시각이 다를 수 있음을 확인합니다.
 * 잘못된 기간으로 종료된 시즌이 재생성되지 않도록 검증합니다.
 */
class SeasonSchedulingTest {

    private static final Instant START = Instant.parse("2026-11-29T15:00:00Z");
    private static final Instant END = Instant.parse("2026-12-27T15:00:00Z");

    /**
     * 현재 구간에 SCHEDULED 시즌을 만듭니다.
     * 실제 생성 시각을 보존하고 원본 달력 구간은 유지합니다.
     * 종료 출처는 시즌 종료 전이므로 NULL이어야 합니다.
     */
    @Test
    void scheduledSeasonUsesActualCreationTime() {
        Instant now = START.plusSeconds(3 * 86400);
        Season season = Season.schedule(2, START, END, now);

        assertEquals(2, season.getSeasonNumber());
        assertEquals("SCHEDULED", season.getStatus());
        assertEquals(START, season.getStartAt());
        assertEquals(END, season.getEndAt());
        assertEquals(now, season.getCreatedAt());
        assertEquals(now, season.getUpdatedAt());
        assertNull(season.getClosingFromStatus());
    }

    /**
     * 이미 만료된 4주 구간에 시즌을 생성할 수 없습니다.
     * 서버가 오래 중단된 뒤 과거 시즌을 소급 생성하지 않습니다.
     * 종료 경계 시각도 허용하지 않습니다.
     */
    @Test
    void expiredWindowIsRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> Season.schedule(2, START, END, END));
    }

    /**
     * 첫 시작 이전 시각으로 시즌을 예약하지 않습니다.
     * 정상화 서비스는 해당 구간에 들어간 뒤 생성합니다.
     * 부정확한 사전 생성 시간을 막습니다.
     */
    @Test
    void futureWindowIsRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> Season.schedule(2, START, END, START.minusSeconds(1)));
    }

    /**
     * 28일이 아닌 잘못된 시즌 종료 시각을 거절합니다.
     * 기존 첫 시즌의 4주 고정 달력과 불일치를 막습니다.
     * 사용자가 임의의 종료 시각을 전달하더라도 저장하지 않습니다.
     */
    @Test
    void invalidDurationIsRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> Season.schedule(2, START, END.plusSeconds(1), START));
    }

    /**
     * 시즌 번호는 1 이상이어야 합니다.
     * 달력 인덱스 0과 DB season_number를 혼동하지 않습니다.
     * 음수나 0 시즌 번호가 저장되지 않도록 차단합니다.
     */
    @Test
    void invalidSeasonNumberIsRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> Season.schedule(0, START, END, START));
    }
}
