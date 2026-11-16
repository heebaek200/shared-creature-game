package com.team.sharedcreature.season.integration;

import com.team.sharedcreature.admin.application.SchedulerJobRunner;
import com.team.sharedcreature.season.application.SeasonCloseJobService;
import com.team.sharedcreature.season.infrastructure.SeasonRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
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

import static org.junit.jupiter.api.Assertions.*;

/**
 * 실제 MySQL에서 종료 Job의 멱등성과 Scheduler 실행 이력을 검증합니다.
 * 서버 종료 후 ENDED만 저장된 경우의 성공 기록 복구도 재현합니다.
 * test 프로필의 자동 트리거는 비활성화하고 명시한 시즌만 직접 처리합니다.
 */
@SpringBootTest
@ActiveProfiles("test")
@EnabledIfEnvironmentVariable(named = "TEST_DB_USERNAME", matches = ".+")
class SeasonCloseSchedulerIntegrationTest {

    @Autowired private JdbcTemplate jdbc;
    @Autowired private SeasonCloseJobService closeJobService;
    @Autowired private SeasonRepository seasonRepository;

    private Long seasonId;
    private Instant endAt;

    /**
     * 테스트별로 서로 충돌하지 않는 과거 SCHEDULED 시즌을 생성합니다.
     * 모든 자동 트리거는 비활성화되어 있으며 직접 실행만 수행합니다.
     * 과거 시즌이므로 부화나 퀘스트 생성은 실행되지 않습니다.
     */
    @BeforeEach
    void setUp() {
        int seasonNumber = ThreadLocalRandom.current().nextInt(400_000_000, 900_000_000);
        Instant startAt = Instant.parse("2020-01-01T00:00:00Z").plusMillis(seasonNumber);
        endAt = startAt.plus(Duration.ofDays(28));

        // 각 테스트 시즌은 서로 다른 season_number와 start_at을 사용합니다.
        jdbc.update("""
                INSERT INTO seasons (
                    season_number
                  , start_at
                  , end_at
                  , status
                  , version
                  , created_at
                  , updated_at
                ) VALUES (?, ?, ?, 'SCHEDULED', 0, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
                """, seasonNumber, Timestamp.from(startAt), Timestamp.from(endAt));
        seasonId = jdbc.queryForObject(
                "SELECT id FROM seasons WHERE season_number = ?", Long.class, seasonNumber
        );
    }

    /**
     * 각 테스트에서 생성한 Scheduler 실행과 시즌 행만 정리합니다.
     * 실행 이력에는 시즌 외래키가 없으므로 논리 종료 시각으로 범위를 한정합니다.
     * 다른 시즌이나 기존 개발 환경의 데이터를 수정하지 않습니다.
     */
    @AfterEach
    void tearDown() {
        if (seasonId == null) {
            return;
        }

        // 종료 이력부터 삭제한 뒤 테스트 시즌만 제거합니다.
        jdbc.update("""
                DELETE FROM scheduler_executions
                 WHERE job_type = 'SEASON_CLOSE'
                   AND scheduled_for = ?
                """, Timestamp.from(endAt));
        jdbc.update("DELETE FROM seasons WHERE id = ?", seasonId);
    }

    /**
     * 만료 SCHEDULED 시즌의 종료 Job을 최초 실행합니다.
     * 랭킹을 소급 생성하지 않고 ENDED 및 Scheduler SUCCEEDED를 Commit합니다.
     * DB 조회를 통해 호출 종료 후의 실제 상태를 확인합니다.
     */
    @Test
    void expiredScheduledSeasonEndsAndRecordsSuccess() {
        assertTrue(seasonRepository.findIdsNeedingCloseNormalization(Instant.now())
                .contains(seasonId));

        assertEquals(SchedulerJobRunner.ExecutionResult.SUCCEEDED,
                closeJobService.runSeasonClose(seasonId, "integration-instance-a"));

        assertEquals("ENDED", storedSeasonStatus());
        assertEquals("SUCCEEDED", storedExecutionStatus());
        assertEquals(0L, count("season_group_rank_snapshots"));
        assertEquals(0L, count("season_member_rank_snapshots"));
    }

    /**
     * 이미 성공한 종료 Job은 같은 키로 재실행되지 않습니다.
     * 종료 시각과 실행 이력의 UNIQUE 키가 동일하게 유지되어야 합니다.
     * 후보 조회에서도 성공한 ENDED 시즌을 제외합니다.
     */
    @Test
    void successfulJobIsNotReExecuted() {
        assertEquals(SchedulerJobRunner.ExecutionResult.SUCCEEDED,
                closeJobService.runSeasonClose(seasonId, "integration-instance-a"));
        Long version = jdbc.queryForObject(
                "SELECT version FROM seasons WHERE id = ?", Long.class, seasonId
        );

        // 성공 이력의 존재가 재호출 실행을 차단합니다.
        assertEquals(SchedulerJobRunner.ExecutionResult.SKIPPED,
                closeJobService.runSeasonClose(seasonId, "integration-instance-b"));
        assertFalse(seasonRepository.findIdsNeedingCloseNormalization(Instant.now())
                .contains(seasonId));
        assertEquals(version, jdbc.queryForObject(
                "SELECT version FROM seasons WHERE id = ?", Long.class, seasonId
        ));
        assertEquals(1L, jdbc.queryForObject("""
                SELECT COUNT(*) FROM scheduler_executions
                 WHERE job_type = 'SEASON_CLOSE'
                   AND scheduled_for = ?
                """, Long.class, Timestamp.from(endAt)));
    }

    /**
     * ENDED만 Commit된 뒤 서버가 종료된 장애 상황을 재현합니다.
     * 성공 이력 없는 ENDED 시즌을 후보 조회가 다시 발견해야 합니다.
     * 종료 데이터는 다시 변경하지 않고 Scheduler 성공 이력만 생성합니다.
     */
    @Test
    void endedWithoutSuccessHistoryIsRecovered() {
        jdbc.update("""
                UPDATE seasons
                   SET status = 'ENDED', closing_from_status = 'SCHEDULED'
                 WHERE id = ?
                """, seasonId);
        Long version = jdbc.queryForObject(
                "SELECT version FROM seasons WHERE id = ?", Long.class, seasonId
        );

        // 업무는 이미 끝났지만 성공 기록만 누락된 종료 작업입니다.
        assertTrue(seasonRepository.findIdsNeedingCloseNormalization(Instant.now())
                .contains(seasonId));
        assertEquals(SchedulerJobRunner.ExecutionResult.SUCCEEDED,
                closeJobService.runSeasonClose(seasonId, "integration-instance-a"));

        assertEquals("ENDED", storedSeasonStatus());
        assertEquals("SUCCEEDED", storedExecutionStatus());
        assertEquals(version, jdbc.queryForObject(
                "SELECT version FROM seasons WHERE id = ?", Long.class, seasonId
        ));
        assertEquals("true", jdbc.queryForObject("""
                SELECT JSON_UNQUOTE(JSON_EXTRACT(result_summary, '$.alreadyEnded'))
                  FROM scheduler_executions
                 WHERE job_type = 'SEASON_CLOSE' AND scheduled_for = ?
                """, String.class, Timestamp.from(endAt)));
    }

    /**
     * 이전 실행자의 Lease가 만료된 상태에서 이미 ENDED된 시즌을 복구합니다.
     * DB 실행 이력의 attempt_count가 증가한 뒤 성공이 저장돼야 합니다.
     * 이전 실행자가 갱신할 수 없도록 owner ID가 새 값으로 변경됩니다.
     */
    @Test
    void expiredRunningLeaseIsReclaimedAfterEndedCommit() {
        jdbc.update("""
                UPDATE seasons
                   SET status = 'ENDED', closing_from_status = 'SCHEDULED'
                 WHERE id = ?
                """, seasonId);
        jdbc.update("""
                INSERT INTO scheduler_executions (
                    job_type
                  , scheduled_for
                  , status
                  , attempt_count
                  , owner_instance_id
                  , started_at
                  , heartbeat_at
                  , lease_expires_at
                  , created_at
                  , updated_at
                ) VALUES (
                    'SEASON_CLOSE', ?, 'RUNNING', 1, 'old-instance'
                  , UTC_TIMESTAMP(6), UTC_TIMESTAMP(6)
                  , UTC_TIMESTAMP(6) - INTERVAL 1 SECOND
                  , UTC_TIMESTAMP(6), UTC_TIMESTAMP(6)
                )
                """, Timestamp.from(endAt));

        // 종료 트랜잭션은 재수행하지 않고 만료된 실행권만 재선점합니다.
        assertEquals(SchedulerJobRunner.ExecutionResult.SUCCEEDED,
                closeJobService.runSeasonClose(seasonId, "recovery-instance"));
        assertEquals("SUCCEEDED", storedExecutionStatus());
        assertEquals(2, jdbc.queryForObject("""
                SELECT attempt_count FROM scheduler_executions
                 WHERE job_type = 'SEASON_CLOSE' AND scheduled_for = ?
                """, Integer.class, Timestamp.from(endAt)));
        assertEquals("recovery-instance", jdbc.queryForObject("""
                SELECT owner_instance_id FROM scheduler_executions
                 WHERE job_type = 'SEASON_CLOSE' AND scheduled_for = ?
                """, String.class, Timestamp.from(endAt)));
    }

    /**
     * CLOSING 중 서버가 중단됐어도 그 상태를 이어서 처리합니다.
     * 종료 출처가 SCHEDULED라면 그룹·개인 Snapshot은 0건이어야 합니다.
     * 기존 시즌 시작·종료 시각은 변경하지 않습니다.
     */
    @Test
    void incompleteClosingSeasonIsResumed() {
        jdbc.update("""
                UPDATE seasons
                   SET status = 'CLOSING', closing_from_status = 'SCHEDULED'
                 WHERE id = ?
                """, seasonId);

        assertTrue(seasonRepository.findIdsNeedingCloseNormalization(Instant.now())
                .contains(seasonId));
        assertEquals(SchedulerJobRunner.ExecutionResult.SUCCEEDED,
                closeJobService.runSeasonClose(seasonId, "integration-instance-a"));
        assertEquals("ENDED", storedSeasonStatus());
        assertEquals("SUCCEEDED", storedExecutionStatus());
    }

    /**
     * 아직 종료 시각에 도달하지 않은 시즌은 자동 정상화 대상이 아닙니다.
     * 상태가 SCHEDULED여도 end_at이 현재보다 미래면 검색에서 제외합니다.
     * 예정되지 않은 종료를 미리 수행하지 않도록 시간 경계를 검증합니다.
     */
    @Test
    void futureScheduledSeasonIsNotCandidate() {
        Instant futureEnd = Instant.now().plus(Duration.ofDays(14));
        jdbc.update("UPDATE seasons SET end_at = ? WHERE id = ?",
                Timestamp.from(futureEnd), seasonId);

        // 현재 저장된 endAt을 기준으로만 후보를 선택합니다.
        assertFalse(seasonRepository.findIdsNeedingCloseNormalization(Instant.now())
                .contains(seasonId));
    }

    /**
     * 종료 시각이 지난 ACTIVE 시즌을 정상화 후보로 조회합니다.
     * 배타 잠금과 랭킹 처리는 후속 종료 Job의 전담 책임입니다.
     * 이번 테스트는 후보 발견 조건만 검사합니다.
     */
    @Test
    void expiredActiveSeasonIsCandidate() {
        jdbc.update("UPDATE seasons SET status = 'ACTIVE' WHERE id = ?", seasonId);

        assertTrue(seasonRepository.findIdsNeedingCloseNormalization(Instant.now())
                .contains(seasonId));
    }

    /**
     * 현재 테스트 시즌의 Commit된 상태를 JDBC로 읽습니다.
     * JPA 영속성 컨텍스트의 변경 감지와 별개로 실제 DB 결과를 확인합니다.
     * 트랜잭션이 종료되기 전에 성공을 주장하지 않도록 구성합니다.
     */
    private String storedSeasonStatus() {
        return jdbc.queryForObject(
                "SELECT status FROM seasons WHERE id = ?", String.class, seasonId
        );
    }

    /**
     * 해당 논리 종료 시각의 Scheduler 실행 이력을 조회합니다.
     * 실행 키에 시즌 종료 예정 시각이 사용됐는지 함께 검증합니다.
     * 재기동한 실제 시각으로 이력을 만드는 오류를 찾아냅니다.
     */
    private String storedExecutionStatus() {
        return jdbc.queryForObject("""
                SELECT status FROM scheduler_executions
                 WHERE job_type = 'SEASON_CLOSE' AND scheduled_for = ?
                """, String.class, Timestamp.from(endAt));
    }

    /**
     * 테스트 시즌과 연결된 그룹·개인 Snapshot 행 수를 검사합니다.
     * 하드코딩한 두 종류의 Snapshot 테이블명만 허용합니다.
     * 다른 테스트 시즌의 데이터는 집계에 포함하지 않습니다.
     */
    private long count(String tableName) {
        if (!"season_group_rank_snapshots".equals(tableName)
                && !"season_member_rank_snapshots".equals(tableName)) {
            throw new IllegalArgumentException("허용되지 않는 Snapshot 테이블입니다.");
        }
        Long value = jdbc.queryForObject(
                "SELECT COUNT(*) FROM " + tableName + " WHERE season_id = ?", Long.class, seasonId
        );
        return java.util.Objects.requireNonNull(value, "count");
    }
}
