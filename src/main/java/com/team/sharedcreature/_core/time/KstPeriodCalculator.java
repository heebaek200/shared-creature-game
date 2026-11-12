package com.team.sharedcreature._core.time;

import org.springframework.stereotype.Component;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.TemporalAdjusters;
import java.util.Objects;

/**
 * KST 기준의 일일 , 주간 업무 기간을 계산하는 컴포넌트입니다.
 * 서버의 기본 타임존에 의존하지 않습니다.
 * 결과 시각은 DB 저장에 사용할 UTC Instant로 반환합니다.
 *
 * Service에서 생성자 주입으로 활용할 수 있도록 @Component 사용
 */
@Component
public class KstPeriodCalculator {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");

    /**
     * 업무 기간의 시작 날짜와 시각 범위를 보관하는 레코드
     */
    public record PeriodWindow(
            LocalDate periodDate,       // KST 날짜
            Instant startAt,            // UTC 기준
            Instant endAt               // UTC 기준
    ) {}

    /**
     * 기준 시각이 속한 KST 일일 기간을 계산함
     *      자정 (이상) 일일 기간 (미만) 다음 날 자정
     */
    public PeriodWindow dailyAt(Instant now) {
        LocalDate date = Objects.requireNonNull(now, "now")
                .atZone(KST)
                .toLocalDate();

        // KST 자정을 UTC Instant로 변환
        return createWindow(date, date.plusDays(1));
    }

    /**
     * 기준 시각이 속한 KST 주간 기간을 계산함
     *      월요일 자정 (이상) 주간 기간 (미만) 다음 월요일 자정
     */
    public PeriodWindow weeklyAt(Instant now) {
        LocalDate date = Objects.requireNonNull(now, "now")
                .atZone(KST)
                .toLocalDate();

        // 이 주의 월요일
        LocalDate monday = date.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));

        return createWindow(monday, monday.plusWeeks(1));
    }

    /**
     * 두 KST 날짜 사이의 기간을 생성. 실제 시각은 UTC Instant로 변환.
     */
    public PeriodWindow createWindow(LocalDate startDate, LocalDate endDate) {
        return new PeriodWindow(
                startDate,
                startDate.atStartOfDay(KST).toInstant(),
                endDate.atStartOfDay(KST).toInstant()
        );
    }

}
