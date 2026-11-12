package com.team.sharedcreature.quest.application;

import org.springframework.stereotype.Component;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.time.temporal.TemporalAdjusters;
import java.util.Objects;
import java.util.Optional;

/**
 * 최초 시즌 시작일을 기준으로 현재 KST 일일·주간 퀘스트 기간을 계산합니다.
 * 영속화나 시즌 활성 상태 확인은 담당하지 않는 순수한 기간 계산 컴포넌트입니다.
 * 만료 기간의 소급 생성 여부는 후속 정상화 Service에서 판단합니다.
 */
@Component
public class QuestPeriodCalendar {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");

    /**
     * 현재 퀘스트 기간을 계산합니다.
     * 최초 시즌 시작 시각은 반드시 KST 월요일 00:00이어야 합니다.
     * 최초 시즌 시작 이전이라면 기간을 생성하지 않고 Optional.empty()를 반환합니다.
     *
     * @param firstSeasonStart 최초 시즌 시작 시각(UTC Instant)
     * @param now 현재 업무 시각(UTC Instant)
     * @param periodType DAILY 또는 WEEKLY
     * @return 현재 시각을 포함하는 기간과 첫 시즌부터의 누적 순회 인덱스
     */
    public Optional<PeriodWindow> currentWindow(
            Instant firstSeasonStart,
            Instant now,
            String periodType
    ) {
        Objects.requireNonNull(firstSeasonStart, "firstSeasonStart");
        Objects.requireNonNull(now, "now");
        Objects.requireNonNull(periodType, "periodType");

        // 설정 오류를 조기에 발견하도록 최초 시즌 달력 기준을 검증합니다.
        ZonedDateTime originKst = firstSeasonStart.atZone(KST);
        if (originKst.getDayOfWeek() != DayOfWeek.MONDAY
                || !originKst.toLocalTime().equals(LocalTime.MIDNIGHT)) {
            throw new IllegalArgumentException("최초 시즌은 KST 월요일 00:00에 시작해야 합니다.");
        }
        if (!"DAILY".equals(periodType) && !"WEEKLY".equals(periodType)) {
            throw new IllegalArgumentException("지원하지 않는 퀘스트 기간 유형: " + periodType);
        }

        // 서비스 최초 시즌 이전 시각에는 유효한 퀘스트 기간이 존재하지 않습니다.
        if (now.isBefore(firstSeasonStart)) {
            return Optional.empty();
        }

        LocalDate originDate = originKst.toLocalDate();
        LocalDate today = now.atZone(KST).toLocalDate();
        boolean daily = "DAILY".equals(periodType);

        // 주간 기간은 매주 월요일 00:00, 일일 기간은 매일 00:00에 시작합니다.
        LocalDate startDate = daily
                ? today
                : today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
        LocalDate endDate = startDate.plusDays(daily ? 1 : 7);
        long elapsedDays = ChronoUnit.DAYS.between(originDate, startDate);
        long rotationIndex = daily ? elapsedDays : elapsedDays / 7;

        // DB에는 KST 경계를 UTC Instant로 보관하며 종료 시각은 배타적입니다.
        return Optional.of(new PeriodWindow(
                periodType,
                startDate,
                startDate.atStartOfDay(KST).toInstant(),
                endDate.atStartOfDay(KST).toInstant(),
                rotationIndex
        ));
    }

    /**
     * 퀘스트 기간 계산 결과입니다.
     * periodDate는 해당 기간이 시작되는 KST 날짜로, 주간은 항상 월요일입니다.
     * [startAt, endAt) 범위와 0부터 시작하는 누적 순회 인덱스를 사용합니다.
     */
    public record PeriodWindow(
            String periodType,
            LocalDate periodDate,
            Instant startAt,
            Instant endAt,
            long rotationIndex
    ) {
    }
}
