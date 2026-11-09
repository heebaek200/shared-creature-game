package com.team.sharedcreature.season.integration;

import com.team.sharedcreature.auth.domain.User;
import com.team.sharedcreature.auth.infrastructure.UserRepository;
import com.team.sharedcreature.group.domain.Group;
import com.team.sharedcreature.group.infrastructure.GroupRepository;
import com.team.sharedcreature.season.application.SeasonActivationService;
import com.team.sharedcreature.season.application.SeasonGroupInitializationService;
import com.team.sharedcreature.season.domain.GroupCreature;
import com.team.sharedcreature.season.domain.GroupSeason;
import com.team.sharedcreature.season.domain.Season;
import com.team.sharedcreature.season.domain.SeasonGroupScore;
import com.team.sharedcreature.season.infrastructure.GroupCreatureRepository;
import com.team.sharedcreature.season.infrastructure.GroupSeasonRepository;
import com.team.sharedcreature.season.infrastructure.SeasonGroupScoreRepository;
import com.team.sharedcreature.season.infrastructure.SeasonRepository;
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
import org.springframework.test.context.ActiveProfiles;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.ThreadLocalRandom;

import static org.junit.jupiter.api.Assertions.*;

/**
 * MySQL에서 그룹 초기화 완료 집계와 SCHEDULED → ACTIVE Commit을 검증합니다.
 * 테스트 메서드는 트랜잭션으로 감싸지 않아 독립 Commit과 행 잠금을 직접 검사합니다.
 * TEST_DB_USERNAME이 설정된 테스트 전용 DB에서만 실행합니다.
 */
@SpringBootTest
@ActiveProfiles("test")
@EnabledIfEnvironmentVariable(named = "TEST_DB_USERNAME", matches = ".+")
class SeasonActivationIntegrationTest {

    private static final Instant START = Instant.parse("2030-01-06T15:00:00Z");
    private static final Instant END = START.plus(Duration.ofDays(28));
    private static final Instant NOW = START.plus(Duration.ofDays(8));

    @TestConfiguration
    static class TestClockConfiguration {
        /**
         * 실제 시스템 시각 대신 초기화·활성화에 사용할 고정 시각을 제공합니다.
         * 각 트랜잭션이 동일한 UTC Clock을 주입받게 합니다.
         * 테스트 외부의 local·dev 환경에는 영향을 주지 않습니다.
         */
        @Bean
        @Primary
        Clock seasonActivationTestClock() {
            return Clock.fixed(NOW, ZoneOffset.UTC);
        }
    }

    @Autowired private SeasonRepository seasonRepository;
    @Autowired private GroupRepository groupRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private GroupSeasonRepository groupSeasonRepository;
    @Autowired private GroupCreatureRepository creatureRepository;
    @Autowired private SeasonGroupScoreRepository scoreRepository;
    @Autowired private SeasonGroupInitializationService initializationService;
    @Autowired private SeasonActivationService activationService;
    @Autowired private JdbcTemplate jdbc;

    private final List<Long> groupIds = new ArrayList<>();
    private Long seasonId;
    private Long userId;

    /**
     * 테스트 전용 SCHEDULED 시즌과 관리자를 생성합니다.
     * 2030년 시즌 시작 시각을 사용해 다른 단계의 테스트 시즌과 분리합니다.
     * 자동 트리거는 application-test.yaml에서 비활성화해야 합니다.
     */
    @BeforeEach
    void setUp() {
        int seasonNumber = ThreadLocalRandom.current().nextInt(910_000_000, 1_100_000_000);
        User user = userRepository.saveAndFlush(User.register("USER", NOW));
        userId = user.getId();
        seasonId = seasonRepository.saveAndFlush(Season.schedule(
                seasonNumber, START, END, NOW)).getId();
    }

    /**
     * 테스트가 만든 알·점수·그룹 시즌을 외래키 순서로 제거합니다.
     * 별도 트랜잭션의 실제 Commit 결과이므로 직접 JDBC로 정리합니다.
     * 기록해 둔 ID에 해당하는 데이터만 삭제합니다.
     */
    @AfterEach
    void tearDown() {
        if (seasonId != null) {
            // GroupSeason에 매달린 자식 행부터 제거합니다.
            jdbc.update("""
                    DELETE gc FROM group_creatures gc
                      JOIN group_seasons gs ON gs.id = gc.group_season_id
                     WHERE gs.season_id = ?
                    """, seasonId);
            jdbc.update("""
                    DELETE score FROM season_group_scores score
                      JOIN group_seasons gs ON gs.id = score.group_season_id
                     WHERE gs.season_id = ?
                    """, seasonId);
            jdbc.update("DELETE FROM group_seasons WHERE season_id = ?", seasonId);
        }
        for (Long groupId : groupIds) {
            jdbc.update("DELETE FROM game_groups WHERE id = ?", groupId);
        }
        if (seasonId != null) {
            jdbc.update("DELETE FROM seasons WHERE id = ?", seasonId);
        }
        if (userId != null) {
            jdbc.update("DELETE FROM users WHERE id = ?", userId);
        }
    }

    /**
     * 그룹 초기화 배치가 Commit된 뒤에만 시즌이 ACTIVE로 바뀝니다.
     * 재실행은 ALREADY_ACTIVE로 끝나고 version을 다시 증가시키지 않습니다.
     * 기존 알의 72시간 부화 기한도 활성화 이후 변하지 않습니다.
     */
    @Test
    void initializedGroupAllowsActivationAndRepeatedRunIsIdempotent() {
        Group group = createGroup();
        var initialization = initializationService.initializeNextBatch(seasonId);
        assertEquals(SeasonGroupInitializationService.Outcome.COMPLETE, initialization.outcome());

        GroupSeason stored = groupSeasonRepository
                .findSnapshotByGroupIdAndSeasonId(group.getId(), seasonId).orElseThrow();
        Instant deadline = stored.getSelectionDeadlineAt();
        long versionBefore = version();

        // 초기화 Commit 이후에만 시즌 상태를 변경합니다.
        var activated = activationService.activateIfInitializationComplete(seasonId);
        long versionAfter = version();
        var repeated = activationService.activateIfInitializationComplete(seasonId);

        assertEquals(SeasonActivationService.Outcome.ACTIVATED, activated.outcome());
        assertEquals(SeasonActivationService.Outcome.ALREADY_ACTIVE, repeated.outcome());
        assertEquals("ACTIVE", status());
        assertEquals(versionBefore + 1, versionAfter);
        assertEquals(versionAfter, version());
        assertEquals(NOW.plus(Duration.ofHours(72)), deadline);
        assertEquals(deadline, groupSeasonRepository.findById(stored.getId())
                .orElseThrow().getSelectionDeadlineAt());
    }

    /**
     * 그룹 시즌을 만들지 않은 활성 그룹이 있으면 활성화를 보류합니다.
     * 누락된 알·점수를 자동 생성하는 책임은 기존 초기화 배치에 있습니다.
     * 누락 원인과 그룹 수가 검사 결과로 전달돼야 합니다.
     */
    @Test
    void missingGroupSeasonIsReportedAndPreventsActivation() {
        createGroup();

        var result = activationService.activateIfInitializationComplete(seasonId);

        assertEquals(SeasonActivationService.Outcome.INCOMPLETE, result.outcome());
        assertEquals(1, result.inspection().missingGroupSeasons());
        assertEquals("SCHEDULED", status());
    }

    /**
     * 그룹 시즌과 점수가 존재해도 알 행이 빠지면 활성화할 수 없습니다.
     * EXISTS만 확인하던 구현의 누락 문제를 실제 JOIN으로 검증합니다.
     * 누락된 알이 복구되기 전까지 SCHEDULED를 유지해야 합니다.
     */
    @Test
    void missingEggIsReportedAndPreventsActivation() {
        GroupSeason gs = createGroupSeason(createGroup());
        scoreRepository.saveAndFlush(SeasonGroupScore.zero(gs, NOW));

        var result = activationService.activateIfInitializationComplete(seasonId);

        assertEquals(SeasonActivationService.Outcome.INCOMPLETE, result.outcome());
        assertEquals(1, result.inspection().missingEggs());
        assertEquals("SCHEDULED", status());
    }

    /**
     * 그룹 시즌과 알이 존재해도 0점 집계가 누락됐다면 활성화하지 않습니다.
     * 데이터 복구 과정에서 누락된 자식 행을 별도로 확인합니다.
     * 초기화 Service가 이후 복구할 수 있도록 상태를 보존합니다.
     */
    @Test
    void missingScoreIsReportedAndPreventsActivation() {
        GroupSeason gs = createGroupSeason(createGroup());
        creatureRepository.saveAndFlush(GroupCreature.egg(gs, NOW));

        var result = activationService.activateIfInitializationComplete(seasonId);

        assertEquals(SeasonActivationService.Outcome.INCOMPLETE, result.outcome());
        assertEquals(1, result.inspection().missingScores());
        assertEquals("SCHEDULED", status());
    }

    /**
     * SCHEDULED 시즌의 점수 행은 정확히 0점이어야 합니다.
     * 이미 점수가 더해진 비정상 행을 올바른 초기화 완료로 인정하지 않습니다.
     * 잘못된 점수는 삭제하거나 덮어쓰지 않고 운영 확인 대상으로 유지합니다.
     */
    @Test
    void nonZeroScoreBlocksActivation() {
        GroupSeason gs = createGroupSeason(createGroup());
        creatureRepository.saveAndFlush(GroupCreature.egg(gs, NOW));
        SeasonGroupScore score = SeasonGroupScore.zero(gs, NOW);
        score.addScore(10, NOW);
        scoreRepository.saveAndFlush(score);

        var result = activationService.activateIfInitializationComplete(seasonId);

        assertEquals(SeasonActivationService.Outcome.INCOMPLETE, result.outcome());
        assertEquals(1, result.inspection().invalidScores());
        assertEquals("SCHEDULED", status());
    }

    /**
     * 비활성 그룹은 새 시즌 초기화 대상에서 제외합니다.
     * 정상 활성 그룹이 하나도 없어도 ACTIVE 전환 자체는 가능합니다.
     * 이미 저장된 과거 그룹 기록을 건드리지 않습니다.
     */
    @Test
    void zeroActiveGroupsCanActivate() {
        var result = activationService.activateIfInitializationComplete(seasonId);

        assertEquals(SeasonActivationService.Outcome.ACTIVATED, result.outcome());
        assertEquals(0, result.inspection().activeGroups());
        assertEquals("ACTIVE", status());
    }

    /**
     * 다른 두 서버가 동시에 같은 시즌 활성화를 시도해도 한 번만 변경됩니다.
     * 하나의 실행은 ACTIVE로 바꾸고 다른 실행은 멱등 결과를 반환합니다.
     * 시즌 행의 PESSIMISTIC_WRITE와 JPA version이 실제 Commit을 보장해야 합니다.
     *
     * @throws Exception 병렬 DB 실행 오류
     */
    @Test
    void concurrentActivationOccursOnlyOnce() throws Exception {
        Group group = createGroup();
        assertEquals(SeasonGroupInitializationService.Outcome.COMPLETE,
                initializationService.initializeNextBatch(seasonId).outcome());
        assertNotNull(group);

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            // 선점 경합을 만들기 위해 두 워커를 동시에 출발시킵니다.
            Future<SeasonActivationService.ActivationResult> first = pool.submit(() -> {
                waitFor(start);
                return activationService.activateIfInitializationComplete(seasonId);
            });
            Future<SeasonActivationService.ActivationResult> second = pool.submit(() -> {
                waitFor(start);
                return activationService.activateIfInitializationComplete(seasonId);
            });
            start.countDown();

            var results = List.of(first.get(15, TimeUnit.SECONDS), second.get(15, TimeUnit.SECONDS));
            assertEquals(1, results.stream().filter(result ->
                    result.outcome() == SeasonActivationService.Outcome.ACTIVATED).count());
            assertEquals(1, results.stream().filter(result ->
                    result.outcome() == SeasonActivationService.Outcome.ALREADY_ACTIVE).count());
            assertEquals("ACTIVE", status());
        } finally {
            // 워커 오류가 발생해도 대기자를 깨우고 스레드풀을 정리합니다.
            start.countDown();
            pool.shutdownNow();
        }
    }

    /**
     * GroupSeason의 선택 기한이 최초 생성 시각에서 72시간이 아니면 거절합니다.
     * 기존 기한을 임의로 다시 계산하거나 연장하지 않습니다.
     * SQL의 DATE_ADD 비교가 실제 DB에서 작동하는지 검증합니다.
     */
    @Test
    void wrongSelectionDeadlineBlocksActivation() {
        Group group = createGroup();
        initializationService.initializeNextBatch(seasonId);
        GroupSeason gs = groupSeasonRepository
                .findSnapshotByGroupIdAndSeasonId(group.getId(), seasonId).orElseThrow();

        // 정상적으로 초기화된 기한을 1시간 늘려서 정합성 오류를 만듭니다.
        jdbc.update("""
                UPDATE group_seasons
                   SET selection_deadline_at = DATE_ADD(selection_deadline_at, INTERVAL 1 HOUR)
                 WHERE id = ?
                """, gs.getId());

        var result = activationService.activateIfInitializationComplete(seasonId);

        assertEquals(SeasonActivationService.Outcome.INCOMPLETE, result.outcome());
        assertEquals(1, result.inspection().invalidGroupSeasons());
        assertEquals("SCHEDULED", status());
    }

    /**
     * SCHEDULED 단계에서 알이 이미 부화한 비정상 기록을 거절합니다.
     * 검사 과정에서 원본 크리처의 성장 단계·종 선택 정보를 변경하지 않습니다.
     * 전체 초기화의 정상 종료를 판정하기 전 알 상태를 확인합니다.
     */
    @Test
    void hatchedCreatureBlocksActivation() {
        Group group = createGroup();
        initializationService.initializeNextBatch(seasonId);
        GroupSeason gs = groupSeasonRepository
                .findSnapshotByGroupIdAndSeasonId(group.getId(), seasonId).orElseThrow();

        // SCHEDULED 상태에서 부화한 상황을 의도적으로 만들어 검사합니다.
        jdbc.update("""
                UPDATE group_creatures
                   SET growth_stage = 'HATCHLING'
                 WHERE group_season_id = ?
                """, gs.getId());

        var result = activationService.activateIfInitializationComplete(seasonId);

        assertEquals(SeasonActivationService.Outcome.INCOMPLETE, result.outcome());
        assertEquals(1, result.inspection().invalidEggs());
        assertEquals("SCHEDULED", status());
    }

    /**
     * 검사 시작 전 이미 만료된 SCHEDULED 시즌은 ACTIVE로 바꾸지 않습니다.
     * 해당 시즌의 이후 처리는 CLOSING Scheduler에 위임해야 합니다.
     * 데이터 수와 관계없이 시간 경계를 우선 적용합니다.
     */
    @Test
    void expiredScheduledSeasonDoesNotActivate() {
        jdbc.update("UPDATE seasons SET end_at = ? WHERE id = ?",
                java.sql.Timestamp.from(NOW), seasonId);

        var result = activationService.activateIfInitializationComplete(seasonId);

        assertEquals(SeasonActivationService.Outcome.EXPIRED, result.outcome());
        assertEquals("SCHEDULED", status());
    }

    /**
     * ACTIVE 전환 Service는 GroupSeason이나 자식 기록을 신규 생성하지 않습니다.
     * 그룹별 알·점수 생성은 STEP 4-E-2의 초기화 Service가 담당합니다.
     * 실제 DB의 그룹 시즌 행 개수를 전후로 비교해 책임 분리를 검증합니다.
     */
    @Test
    void noAdditionalRowsAreInsertedByActivation() {
        Group group = createGroup();
        var initialization = initializationService.initializeNextBatch(seasonId);
        assertEquals(1, initialization.createdGroupSeasons());
        assertNotNull(group);

        long before = countGroupSeasons();
        activationService.activateIfInitializationComplete(seasonId);
        long after = countGroupSeasons();

        assertEquals(before, after);
        assertEquals(1, after);
    }

    /**
     * DB에 이미 저장한 테스트 사용자를 주 관리자로 설정합니다.
     * 다른 테스트와 중복되지 않는 그룹명을 사용합니다.
     * 실제 그룹 생성 도메인 팩토리로 기본 ACTIVE 상태를 보장합니다.
     */
    private Group createGroup() {
        User admin = userRepository.findById(userId).orElseThrow();
        String suffix = Long.toString(System.nanoTime());
        Group group = groupRepository.saveAndFlush(
                Group.create("e3" + suffix, "e3" + suffix, "", admin, NOW));
        groupIds.add(group.getId());
        return group;
    }

    /**
     * 그룹 시즌 행을 생성하되 알과 점수 행은 만들지 않습니다.
     * 누락된 하위 행을 검증하는 테스트의 시작 상태로 사용합니다.
     * 실제 최초 생성 시각부터 72시간 기한이 저장됩니다.
     */
    private GroupSeason createGroupSeason(Group group) {
        Season season = seasonRepository.findById(seasonId).orElseThrow();
        return groupSeasonRepository.saveAndFlush(GroupSeason.start(group, season, NOW));
    }

    /**
     * 동시 호출 테스트에서 두 작업이 같은 시작 신호를 받도록 기다립니다.
     * 무제한 대기하지 않으며 인터럽트 상태를 보존합니다.
     * 잠금 경쟁의 결과를 결정적으로 검사하기 위한 헬퍼입니다.
     */
    private void waitFor(CountDownLatch latch) {
        try {
            if (!latch.await(5, TimeUnit.SECONDS)) {
                throw new IllegalStateException("활성화 동시 테스트 시작 신호가 없습니다.");
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("활성화 동시 테스트가 중단됐습니다.", exception);
        }
    }

    /**
     * JDBC를 이용해 실제 Commit된 시즌 상태를 읽습니다.
     * 트랜잭션에 남아있는 JPA 영속성 컨텍스트 캐시를 사용하지 않습니다.
     * 통합 테스트의 최종 상태 검증에 사용합니다.
     */
    private String status() {
        return jdbc.queryForObject(
                "SELECT status FROM seasons WHERE id = ?", String.class, seasonId);
    }

    /**
     * DB에 Commit된 낙관적 잠금 버전을 직접 조회합니다.
     * 동일 시즌 중복 ACTIVE 전환 시 불필요하게 증가하지 않아야 합니다.
     * JPA 저장 상태의 변화를 관찰할 때 사용합니다.
     */
    private long version() {
        return Objects.requireNonNull(jdbc.queryForObject(
                "SELECT version FROM seasons WHERE id = ?", Long.class, seasonId));
    }

    /**
     * 테스트 시즌에 속하는 그룹 시즌 행의 수를 셉니다.
     * ACTIVE 전환 단계에서 추가 그룹 참여를 생성하지 않았는지 검증합니다.
     * 임의 SQL 테이블명을 외부 입력으로 받지 않습니다.
     */
    private long countGroupSeasons() {
        return Objects.requireNonNull(jdbc.queryForObject(
                "SELECT COUNT(*) FROM group_seasons WHERE season_id = ?", Long.class, seasonId));
    }
}
