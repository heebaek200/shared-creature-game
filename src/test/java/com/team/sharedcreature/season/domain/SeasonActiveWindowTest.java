
package com.team.sharedcreature.season.domain;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 시즌 유효성 판정의 상태 및 시간 경계를 검증합니다.
 * 실제 DB와 Spring Context 없이 도메인 로직만 테스트합니다.
 * 시작 포함·종료 제외 원칙을 중점적으로 확인합니다.
 */
class SeasonActiveWindowTest {

    private static final Instant START =
            Instant.parse("2026-11-01T15:00:00Z");

    private static final Instant END =
            Instant.parse("2026-11-29T15:00:00Z");

    /**
     * 시즌 시작 시각은 유효 기간에 포함됩니다.
     * DB 상태가 ACTIVE인 경우를 검증합니다.
     * 정확히 시작 시각에도 정상 사용 가능해야 합니다.
     */
    @Test
    void activeAtExactStart() {
        Season season = season("ACTIVE");

        assertTrue(season.isActiveAt(START));
    }

    /**
     * 시즌 시작 이전의 접근을 거절합니다.
     * DB 상태가 ACTIVE여도 시작 전에는 사용할 수 없습니다.
     * 경계 직전 1초를 기준으로 확인합니다.
     */
    @Test
    void inactiveBeforeStart() {
        Season season = season("ACTIVE");

        assertFalse(season.isActiveAt(START.minusSeconds(1)));
    }

    /**
     * 시즌 종료 직전까지 정상 사용 가능한지 검증합니다.
     * 종료 시각은 유효 기간에 포함되지 않습니다.
     * 종료 1초 전에는 여전히 ACTIVE로 판정해야 합니다.
     */
    @Test
    void activeJustBeforeEnd() {
        Season season = season("ACTIVE");

        assertTrue(season.isActiveAt(END.minusSeconds(1)));
    }

    /**
     * 종료 시각에 도달한 시즌은 사용할 수 없습니다.
     * DB 상태 변경이 지연되어도 종료 경계가 우선합니다.
     * 종료와 정확히 같은 시각을 검증합니다.
     */
    @Test
    void inactiveAtExactEnd() {
        Season season = season("ACTIVE");

        assertFalse(season.isActiveAt(END));
    }

    /**
     * ACTIVE가 아닌 시즌은 시간 조건과 무관하게 거절합니다.
     * 초기화 중이거나 종료 중인 시즌도 현재 시즌이 아닙니다.
     * SCHEDULED, CLOSING, ENDED 상태를 모두 검증합니다.
     */
    @Test
    void nonActiveStatusesAreRejected() {
        Instant duringSeason = START.plusSeconds(3600);

        // 상태가 우선이며 기간 내부라고 허용하지 않습니다.
        for (String status : new String[]{
                "SCHEDULED", "CLOSING", "ENDED"
        }) {
            assertFalse(
                    season(status).isActiveAt(duringSeason),
                    "Unexpected active status: " + status
            );
        }
    }

    /**
     * 테스트에 사용할 시즌 엔티티를 준비합니다.
     * 필드 접근을 통해 영속화 전 상태를 재현합니다.
     * 상태와 실제 기간은 테스트별로 동일하게 사용합니다.
     */
    private Season season(String status) {
        Season season = new Season();

        // JPA 전용 생성자를 사용하는 테스트 Fixture입니다.
        ReflectionTestUtils.setField(season, "status", status);
        ReflectionTestUtils.setField(season, "startAt", START);
        ReflectionTestUtils.setField(season, "endAt", END);

        return season;
    }
}
