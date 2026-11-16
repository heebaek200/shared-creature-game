package com.team.sharedcreature.quest.application;

import org.junit.jupiter.api.Test;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * KST 퀘스트 기간 계산과 최초 시즌 기준 누적 순회 인덱스를 검증합니다.
 * Java의 시스템 기본 시간대와 무관하게 고정 Instant로 테스트합니다.
 * DB를 사용하지 않으므로 기간 계산만 빠르게 회귀 검증합니다.
 */
class QuestPeriodCalendarTest {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    private static final Instant FIRST_START = Instant.parse("2026-11-01T15:00:00Z");
    private final QuestPeriodCalendar calendar = new QuestPeriodCalendar();

    /**
     * 최초 시즌 시작 순간은 일일·주간 모두 순회 인덱스 0입니다.
     * KST 11월 2일 월요일 자정은 UTC로 11월 1일 15시입니다.
     * 종료 시각은 각각 다음 날과 다음 주 월요일의 시작입니다.
     */
    @Test
    void firstSeasonStartCreatesFirstDailyAndWeeklyWindows() {
        var daily = calendar.currentWindow(FIRST_START, FIRST_START, "DAILY").orElseThrow();
        var weekly = calendar.currentWindow(FIRST_START, FIRST_START, "WEEKLY").orElseThrow();

        // 첫 시즌의 KST 월요일 자정이 두 기간의 공통 시작입니다.
        assertEquals(LocalDate.of(2026, 11, 2), daily.periodDate());
        assertEquals(FIRST_START, daily.startAt());
        assertEquals(kst(2026, 11, 3, 0, 0), daily.endAt());
        assertEquals(0L, daily.rotationIndex());
        assertEquals(FIRST_START, weekly.startAt());
        assertEquals(kst(2026, 11, 9, 0, 0), weekly.endAt());
        assertEquals(0L, weekly.rotationIndex());
    }

    /**
     * 자정 직전은 전날 일일 기간, 자정 직후는 다음 날 기간입니다.
     * 기간의 종료는 exclusive이므로 정확히 00:00에 인덱스가 증가합니다.
     * 시각 비교에 시스템 기본 타임존을 사용하지 않습니다.
     */
    @Test
    void dailyWindowChangesAtKstMidnight() {
        var before = calendar.currentWindow(
                FIRST_START, kst(2026, 11, 3, 0, 0).minusNanos(1), "DAILY").orElseThrow();
        var at = calendar.currentWindow(FIRST_START, kst(2026, 11, 3, 0, 0), "DAILY")
                .orElseThrow();

        // 1나노초 경계 차이로 전날/다음 날 기간을 분리합니다.
        assertEquals(0L, before.rotationIndex());
        assertEquals(1L, at.rotationIndex());
        assertEquals(before.endAt(), at.startAt());
    }

    /**
     * 일요일 마지막 순간까지 기존 주간 기간을 사용합니다.
     * 월요일 KST 00:00부터만 다음 주차와 순회 인덱스로 넘어갑니다.
     * 주간 periodDate는 해당 주 월요일로 고정합니다.
     */
    @Test
    void weeklyWindowChangesOnlyOnMondayMidnight() {
        var before = calendar.currentWindow(
                FIRST_START, kst(2026, 11, 9, 0, 0).minusNanos(1), "WEEKLY").orElseThrow();
        var at = calendar.currentWindow(FIRST_START, kst(2026, 11, 9, 0, 0), "WEEKLY")
                .orElseThrow();

        // 월요일에 인덱스가 증가하고, 주간 날짜는 월요일이 됩니다.
        assertEquals(0L, before.rotationIndex());
        assertEquals(1L, at.rotationIndex());
        assertEquals(DayOfWeek.MONDAY, at.periodDate().getDayOfWeek());
        assertEquals(before.endAt(), at.startAt());
    }

    /**
     * 수요일은 같은 주 월요일 시작의 주간 기간에 속합니다.
     * 일일 기간은 수요일 자정부터 목요일 자정까지입니다.
     * 같은 현재 시각에서 일일과 주간 인덱스가 각각 계산됩니다.
     */
    @Test
    void midweekInstantProducesDifferentDailyAndWeeklyIndices() {
        Instant now = kst(2026, 11, 11, 13, 40);
        var daily = calendar.currentWindow(FIRST_START, now, "DAILY").orElseThrow();
        var weekly = calendar.currentWindow(FIRST_START, now, "WEEKLY").orElseThrow();

        // 일일은 최초 시즌 시작 후 9일, 주간은 한 주 경과 상태입니다.
        assertEquals(9L, daily.rotationIndex());
        assertEquals(1L, weekly.rotationIndex());
        assertEquals(LocalDate.of(2026, 11, 11), daily.periodDate());
        assertEquals(LocalDate.of(2026, 11, 9), weekly.periodDate());
    }

    /**
     * 새로운 4주 시즌이 시작되어도 템플릿 순회 번호는 초기화하지 않습니다.
     * 최초 시즌부터 누적 일수 및 주차를 사용합니다.
     * 첫 4주가 지나면 일일 28, 주간 4가 되어야 합니다.
     */
    @Test
    void rotationIndexContinuesAcrossSeasonBoundary() {
        Instant nextSeason = FIRST_START.plusSeconds(28L * 24 * 60 * 60);
        var daily = calendar.currentWindow(FIRST_START, nextSeason, "DAILY").orElseThrow();
        var weekly = calendar.currentWindow(FIRST_START, nextSeason, "WEEKLY").orElseThrow();

        // 시즌 번호와 무관하게 최초 시즌 기준 인덱스를 유지합니다.
        assertEquals(28L, daily.rotationIndex());
        assertEquals(4L, weekly.rotationIndex());
    }

    /**
     * 최초 시즌 이전에는 일일·주간 기간을 임의로 만들지 않습니다.
     * 최초 시즌보다 1나노초 빠른 시각에서도 Optional.empty()가 반환됩니다.
     * 이후 정상화 계층이 시즌 자체의 활성 여부를 추가로 확인합니다.
     */
    @Test
    void noQuestPeriodExistsBeforeFirstSeasonStart() {
        // 시작 시각 전에는 어떤 기간도 생성할 수 없습니다.
        assertFalse(calendar.currentWindow(FIRST_START, FIRST_START.minusNanos(1), "DAILY")
                .isPresent());
        assertFalse(calendar.currentWindow(FIRST_START, FIRST_START.minusNanos(1), "WEEKLY")
                .isPresent());
    }

    /**
     * 최초 시즌이 월요일 KST 자정이 아니라면 설계에 위배됩니다.
     * 별도의 임의 기준 날짜로 인덱스를 계산하지 않습니다.
     * DB 시드 오류를 탐지하기 위해 IllegalArgumentException을 발생시킵니다.
     */
    @Test
    void rejectsInvalidFirstSeasonBoundary() {
        // KST 월요일 자정에서 한 시간 어긋난 시드 값입니다.
        Instant wrongStart = FIRST_START.plusSeconds(3600);
        assertThrows(IllegalArgumentException.class,
                () -> calendar.currentWindow(wrongStart, wrongStart, "DAILY"));
    }

    /**
     * 미지원 기간 유형을 무시하거나 일일로 추정하지 않습니다.
     * 도메인에는 DAILY와 WEEKLY만 존재하기 때문입니다.
     * 잘못된 문자열은 서비스 상단에서 즉시 오류로 판별합니다.
     */
    @Test
    void rejectsUnsupportedPeriodType() {
        // 오타나 임의 입력으로 인해 다른 기간을 배정하지 않도록 합니다.
        assertThrows(IllegalArgumentException.class,
                () -> calendar.currentWindow(FIRST_START, FIRST_START, "MONTHLY"));
    }

    /**
     * 테스트 입력의 한국 표준시를 UTC Instant로 변환합니다.
     * 지역 날짜·시각을 직접 Instant로 변환해 시스템 기본 타임존 개입을 차단합니다.
     * 모든 경계 테스트가 동일한 도우미를 사용하도록 합니다.
     */
    private static Instant kst(int year, int month, int day, int hour, int minute) {
        // KST는 이 프로젝트에서 모든 기간 경계의 기준 시간대입니다.
        return LocalDateTime.of(year, month, day, hour, minute).atZone(KST).toInstant();
    }
}
