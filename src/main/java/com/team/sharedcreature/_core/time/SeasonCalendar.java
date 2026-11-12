package com.team.sharedcreature._core.time;

import org.springframework.stereotype.Component;

import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.Objects;
import java.util.Optional;

/**
 * 최초 시즌 시작 시각을 기준으로 4주 시즌 달력을 계산합니다.
 * 모든 시작 경계는 KST 월요일 00:00으로 고정됩니다.
 */
@Component
public class SeasonCalendar {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    private static final long DAYS_PER_SEASON = 28L;

    /**
     * 4주 시즌 달력의 구간 정보를 나타내는 레코드
     * index는 최초 시즌부터 0-base 번호이며, DB의 season_number와 같은 번호가 아닙니다.
     */
    public record SeasonWindow(
            long index,
            Instant startAt,
            Instant endAt
    ) {}

    /**
     * 현재 시각이 속한 4주 시즌 구간을 계산함
     * 첫 기즌 시작 전이면 빈 결과를 반환함.
     * 중간 시즌을 소급 생성하지 않고 달려 구간만 계산함.
     */
    public Optional<SeasonWindow> currentWindow(Instant firstSeasonStart, Instant now) {
        Objects.requireNonNull(firstSeasonStart, "firstSeasonStart");
        Objects.requireNonNull(now, "now");

        ZonedDateTime firstStartKst = firstSeasonStart.atZone(KST);

        // 최초 시즌은 KST 월요일 자정에 시작해야 하는 규칙
        if (firstStartKst.getDayOfWeek() != DayOfWeek.MONDAY || !firstStartKst.toLocalTime().equals(LocalTime.MIDNIGHT)) {
            throw new IllegalArgumentException("첫 시즌은 반드시 월요일 00:00 시(KST)에 시작해야 합니다.");
        }

        // 서비스의 첫 시즌이 시작되기 전에는 구간이 없음
        if (now.isBefore(firstSeasonStart)) {
            return Optional.empty();
        }

        LocalDate firstDate = firstSeasonStart.atZone(KST).toLocalDate();
        LocalDate currentDate = now.atZone(KST).toLocalDate();

        // KST 달력 날짜 기준 계산
        long elapsedDays = ChronoUnit.DAYS.between(firstDate, currentDate);
        long windowIndex = elapsedDays / DAYS_PER_SEASON;

        // 4주 단위의 시작-종료 날짜 계산
        LocalDate startDate = firstDate.plusWeeks(Math.multiplyExact(windowIndex, 4L));
        LocalDate endDate   = startDate.plusWeeks(4);

        return Optional.of(new SeasonWindow(
                windowIndex,
                startDate.atStartOfDay(KST).toInstant(),
                endDate.atStartOfDay(KST).toInstant()
        ));
    }

}
