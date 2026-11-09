package com.team.sharedcreature.season.integration;

import com.team.sharedcreature.auth.domain.User;
import com.team.sharedcreature.auth.infrastructure.UserRepository;
import com.team.sharedcreature.group.domain.Group;
import com.team.sharedcreature.group.infrastructure.GroupRepository;
import com.team.sharedcreature.season.application.SeasonLifecycleNormalizationService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 실제 MySQL에서 만료 시즌 종료부터 새 시즌 활성화까지 전 과정을 확인합니다.
 * 테스트 Profile에는 data.sql 시드가 없으므로 최초 시즌을 명시적으로 생성합니다.
 * 단위 업무의 별도 Commit과 전체 정상화 재실행 멱등성을 함께 검증합니다.
 */
@SpringBootTest
@ActiveProfiles("test")
@EnabledIfEnvironmentVariable(named = "TEST_DB_USERNAME", matches = ".+")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class SeasonLifecycleNormalizationIntegrationTest {

    private static final Instant FIRST_START = Instant.parse("2026-11-01T15:00:00Z");
    private static final Instant FIRST_END = Instant.parse("2026-11-29T15:00:00Z");
    private static final Instant NOW = Instant.parse("2026-12-01T00:00:00Z");

    @TestConfiguration
    static class TestClockConfig {
        /**
         * 첫 시즌 종료 뒤 다음 시즌 구간 안의 고정 시각을 사용합니다.
         * 트리거 없이 같은 정상화 Service가 Clock을 공유하도록 합니다.
         * 테스트 Profile 밖의 local·dev 시각에는 영향을 주지 않습니다.
         */
        @Bean
        @Primary
        Clock seasonLifecycleTestClock() {
            return Clock.fixed(NOW, ZoneOffset.UTC);
        }
    }

    @Autowired private JdbcTemplate jdbc;
    @Autowired private UserRepository userRepository;
    @Autowired private GroupRepository groupRepository;
    @Autowired private SeasonLifecycleNormalizationService normalizer;

    private Long firstSeasonId;
    private Long newSeasonId;
    private Long groupId;
    private Long userId;

    /**
     * 첫 시즌의 KST 월요일 자정 시각을 사용하여 테스트 시드를 삽입합니다.
     * test Profile은 data.sql을 로딩하지 않으므로 최초 기준을 직접 준비합니다.
     * 기존 그룹도 생성해 다음 시즌의 72시간 알 초기화를 검증합니다.
     */
    @BeforeEach
    void setUp() {
        // test Profile의 data-locations: []에 맞춰 필요한 최초 시즌 시드를 직접 생성합니다.
        jdbc.update("""
                INSERT INTO seasons (
                    season_number
                  , start_at
                  , end_at
                  , status
                  , version
                  , created_at
                  , updated_at
                ) VALUES (1, ?, ?, 'SCHEDULED', 0, ?, ?)
                """, Timestamp.from(FIRST_START), Timestamp.from(FIRST_END),
                Timestamp.from(FIRST_START), Timestamp.from(FIRST_START));
        firstSeasonId = jdbc.queryForObject(
                "SELECT id FROM seasons WHERE season_number = 1", Long.class
        );
        assertNotNull(firstSeasonId);
        User user = userRepository.saveAndFlush(User.register("USER", NOW));
        userId = user.getId();
        Group group = groupRepository.saveAndFlush(Group.create(
                "e4통합그룹" + userId,
                "e4-integration-" + userId,
                "integration test",
                user,
                NOW
        ));
        groupId = group.getId();
    }

    /**
     * 테스트가 만든 신규 시즌·알·점수·실행 이력을 외래키 순서로 정리합니다.
     * 직접 만든 최초 시즌 시드도 마지막에 삭제해 테스트를 격리합니다.
     * 이 테스트의 생성·삭제 범위는 전용 DB에서만 사용해야 합니다.
     */
    @AfterEach
    void tearDown() {
        if (newSeasonId != null) {
            // 독립 Commit된 알·점수·참여 이력부터 순서대로 삭제합니다.
            jdbc.update("DELETE FROM group_creatures WHERE group_season_id IN "
                    + "(SELECT id FROM group_seasons WHERE season_id = ?)", newSeasonId);
            jdbc.update("DELETE FROM season_group_scores WHERE group_season_id IN "
                    + "(SELECT id FROM group_seasons WHERE season_id = ?)", newSeasonId);
            jdbc.update("DELETE FROM group_seasons WHERE season_id = ?", newSeasonId);
            jdbc.update("DELETE FROM seasons WHERE id = ?", newSeasonId);
        }
        jdbc.update("""
                DELETE FROM scheduler_executions
                 WHERE job_type = 'SEASON_CLOSE'
                   AND scheduled_for = ?
                """, Timestamp.from(FIRST_END));
        if (groupId != null) {
            jdbc.update("DELETE FROM game_groups WHERE id = ?", groupId);
        }
        if (userId != null) {
            jdbc.update("DELETE FROM users WHERE id = ?", userId);
        }
        if (firstSeasonId != null) {
            jdbc.update("DELETE FROM seasons WHERE id = ?", firstSeasonId);
        }
    }

    /**
     * 첫 시즌 ENDED Commit 뒤 새 시즌을 생성하고 기존 그룹을 초기화합니다.
     * GroupSeason의 72시간 부화 기한과 알·0점 집계가 저장돼야 합니다.
     * 재실행해도 시즌·그룹·알·점수·성공 이력이 추가되지 않아야 합니다.
     */
    @Test
    void expiredSeasonToNewActiveSeasonIsIdempotent() {
        SeasonLifecycleNormalizationService.Result first = normalizer.normalize();
        assertEquals(SeasonLifecycleNormalizationService.Outcome.ACTIVE, first.outcome());
        assertEquals("ENDED", seasonStatus(firstSeasonId));

        // 새 시즌은 최초 시드에서 계산한 다음 4주 구간만 생성합니다.
        newSeasonId = jdbc.queryForObject(
                "SELECT id FROM seasons WHERE season_number = 2", Long.class
        );
        assertEquals(newSeasonId, first.seasonId());
        assertEquals("ACTIVE", seasonStatus(newSeasonId));
        assertEquals(1L, count("SELECT COUNT(*) FROM group_seasons WHERE season_id = ?", newSeasonId));
        assertEquals(1L, count("""
                SELECT COUNT(*) FROM group_creatures gc
                  JOIN group_seasons gs ON gs.id = gc.group_season_id
                 WHERE gs.season_id = ? AND gc.growth_stage = 'EGG'
                """, newSeasonId));
        assertEquals(1L, count("""
                SELECT COUNT(*) FROM season_group_scores score
                  JOIN group_seasons gs ON gs.id = score.group_season_id
                 WHERE gs.season_id = ? AND score.score = 0
                """, newSeasonId));

        // 논리적 시즌 시작과 실제 초기화 시각은 다릅니다.
        Timestamp deadline = jdbc.queryForObject("""
                SELECT gs.selection_deadline_at
                  FROM group_seasons gs
                 WHERE gs.season_id = ?
                """, Timestamp.class, newSeasonId);
        assertEquals(NOW.plus(Duration.ofHours(72)), deadline.toInstant());
        assertEquals(1L, jdbc.queryForObject("""
                SELECT COUNT(*) FROM scheduler_executions
                 WHERE job_type = 'SEASON_CLOSE'
                   AND scheduled_for = ?
                   AND status = 'SUCCEEDED'
                """, Long.class, Timestamp.from(FIRST_END)));

        // 같은 시점의 5분 재점검에서도 모든 DB 이력을 그대로 보존합니다.
        SeasonLifecycleNormalizationService.Result repeated = normalizer.normalize();
        assertEquals(SeasonLifecycleNormalizationService.Outcome.ACTIVE, repeated.outcome());
        assertEquals(1L, count("SELECT COUNT(*) FROM group_seasons WHERE season_id = ?", newSeasonId));
        assertEquals(1L, jdbc.queryForObject(
                "SELECT COUNT(*) FROM seasons WHERE season_number = 2", Long.class
        ));
        Timestamp unchangedDeadline = jdbc.queryForObject("""
                SELECT gs.selection_deadline_at
                  FROM group_seasons gs
                 WHERE gs.season_id = ?
                """, Timestamp.class, newSeasonId);
        assertEquals(deadline.toInstant(), unchangedDeadline.toInstant());
    }

    /**
     * 전용 SQL의 집계 결과가 NULL이면 검증을 실패시킵니다.
     * 테스트에서만 고정한 테이블과 대상 시즌 ID를 사용합니다.
     * 원본·자식 행 수를 개별 SQL로 확인해 JPA 캐시를 피합니다.
     */
    private long count(String sql, Long seasonId) {
        Long result = jdbc.queryForObject(sql, Long.class, seasonId);
        return java.util.Objects.requireNonNull(result, "count");
    }

    /**
     * 별도의 DB 조회를 통해 실제로 Commit된 시즌 상태를 확인합니다.
     * 이미 영속성 컨텍스트에서 읽은 Season 객체는 사용하지 않습니다.
     * 반환 상태는 ACTIVE 또는 ENDED여야 정상화 결과와 맞습니다.
     */
    private String seasonStatus(Long seasonId) {
        return jdbc.queryForObject(
                "SELECT status FROM seasons WHERE id = ?", String.class, seasonId
        );
    }
}
