
package com.team.sharedcreature.season.integration;

import com.team.sharedcreature.season.application.SeasonTransitionService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.ThreadLocalRandom;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 시즌 종료 출처가 실제 MySQL에 보존되는지 검증합니다.
 * ACTIVE와 SCHEDULED의 종료 결과를 각각 확인합니다.
 * 독립 Commit 이후 JDBC 조회로 저장 결과를 확인합니다.
 */
@SpringBootTest
@ActiveProfiles("test")
@EnabledIfEnvironmentVariable(
        named = "TEST_DB_USERNAME",
        matches = ".+"
)
class SeasonClosingOriginIntegrationTest {

    @Autowired
    private SeasonTransitionService transitionService;

    @Autowired
    private JdbcTemplate jdbc;

    /**
     * 두 종류의 시즌 종료 출처가 저장되는지 검증합니다.
     * 종료 처리는 실제 Spring 트랜잭션을 통해 수행합니다.
     * 테스트 데이터는 각 검증이 끝나면 삭제합니다.
     */
    @Test
    void closingOriginIsPersisted() {
        verifyClosingOrigin("ACTIVE");
        verifyClosingOrigin("SCHEDULED");
    }

    /**
     * 지정한 상태에서 종료를 시작하고 DB 저장 결과를 검사합니다.
     * 과거의 4주 구간을 사용하여 항상 만료된 시즌을 만듭니다.
     * 재실행해도 최초 종료 출처가 보존되어야 합니다.
     *
     * @param originalStatus 종료 전 시즌 상태
     */
    private void verifyClosingOrigin(String originalStatus) {
        int seasonNumber = ThreadLocalRandom.current()
                .nextInt(1_000_000_000, 1_900_000_000);

        Instant start = Instant.parse("2020-01-01T00:00:00Z")
                .plusMillis(seasonNumber);

        Instant end = start.plus(Duration.ofDays(28));

        try {
            // 다른 시즌과 충돌하지 않는 테스트 시즌을 생성합니다.
            jdbc.update("""
                    INSERT INTO seasons (
                        season_number
                      , start_at
                      , end_at
                      , status
                      , version
                      , created_at
                      , updated_at
                    ) VALUES (?, ?, ?, ?, 0, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
                    """,
                    seasonNumber,
                    Timestamp.from(start),
                    Timestamp.from(end),
                    originalStatus
            );

            Long seasonId = jdbc.queryForObject("""
                    SELECT id
                      FROM seasons
                     WHERE season_number = ?
                    """, Long.class, seasonNumber);

            // 실제 Service를 통해 종료 상태를 Commit합니다.
            assertThat(
                    transitionService.beginClosingIfExpired(seasonId)
            ).isTrue();

            assertThat(jdbc.queryForObject("""
                    SELECT status
                      FROM seasons
                     WHERE id = ?
                    """, String.class, seasonId))
                    .isEqualTo("CLOSING");

            assertThat(jdbc.queryForObject("""
                    SELECT closing_from_status
                      FROM seasons
                     WHERE id = ?
                    """, String.class, seasonId))
                    .isEqualTo(originalStatus);

            // 재실행하더라도 기존 출처를 유지해야 합니다.
            assertThat(
                    transitionService.beginClosingIfExpired(seasonId)
            ).isFalse();

            assertThat(jdbc.queryForObject("""
                    SELECT closing_from_status
                      FROM seasons
                     WHERE id = ?
                    """, String.class, seasonId))
                    .isEqualTo(originalStatus);

        } finally {
            // 테스트가 생성한 시즌만 정리합니다.
            jdbc.update("""
                    DELETE FROM seasons
                     WHERE season_number = ?
                    """, seasonNumber);
        }
    }
}
