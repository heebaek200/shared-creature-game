package com.team.sharedcreature.season.integration;

import com.team.sharedcreature.auth.domain.User;
import com.team.sharedcreature.auth.infrastructure.UserRepository;
import com.team.sharedcreature.group.domain.Group;
import com.team.sharedcreature.group.infrastructure.GroupRepository;
import com.team.sharedcreature.season.application.SeasonGroupInitializationService;
import com.team.sharedcreature.season.domain.GroupCreature;
import com.team.sharedcreature.season.domain.GroupSeason;
import com.team.sharedcreature.season.domain.Season;
import com.team.sharedcreature.season.infrastructure.GroupCreatureRepository;
import com.team.sharedcreature.season.infrastructure.GroupSeasonRepository;
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
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.ThreadLocalRandom;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 실제 MySQL의 그룹 시즌 초기화, 복구, 행 잠금 직렬화를 검증합니다.
 * 테스트 메서드는 @Transactional을 사용하지 않으며 각 서비스 Commit을 직접 관찰합니다.
 * TEST_DB_USERNAME이 지정된 테스트 전용 DB에서만 실행합니다.
 */
@SpringBootTest
@ActiveProfiles("test")
@EnabledIfEnvironmentVariable(named = "TEST_DB_USERNAME", matches = ".+")
class SeasonGroupInitializationIntegrationTest {

    private static final Instant START = Instant.parse("2027-01-24T15:00:00Z");
    private static final Instant END = START.plus(Duration.ofDays(28));
    private static final Instant NOW = Instant.parse("2027-02-01T00:00:00Z");

    @TestConfiguration
    static class TestClockConfiguration {
        /**
         * 실제 서버 시간 대신 2027년 현재 시즌 안의 시간을 제공합니다.
         * 운영 Clock을 교체하지 않고 테스트 ApplicationContext에만 적용됩니다.
         * 시즌 시작일과 최초 실제 초기화 시각을 분리하여 검증합니다.
         */
        @Bean
        @Primary
        Clock seasonInitializationTestClock() {
            return Clock.fixed(NOW, ZoneOffset.UTC);
        }
    }

    @Autowired private SeasonRepository seasonRepository;
    @Autowired private GroupRepository groupRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private GroupSeasonRepository groupSeasonRepository;
    @Autowired private GroupCreatureRepository creatureRepository;
    @Autowired private SeasonGroupInitializationService service;
    @Autowired private JdbcTemplate jdbc;

    private final List<Long> groupIds = new ArrayList<>();
    private Long seasonId;
    private Long userId;

    /**
     * 하나의 테스트 전용 사용자를 생성합니다.
     * 테스트별 시즌과 그룹은 각 메서드에서 별도로 생성합니다.
     * TEST 프로필의 기존 초기화 시드는 사용하지 않습니다.
     */
    @BeforeEach
    void setUp() {
        User user = userRepository.saveAndFlush(User.register("USER", NOW));
        userId = user.getId();
    }

    /**
     * 테스트가 생성한 크리처·점수·그룹 시즌을 외래키 순서로 삭제합니다.
     * 실제 DB에 Commit된 레코드만 각 테스트에 기록한 ID로 한정합니다.
     * 테스트 케이스 간 외래키 및 그룹명 중복을 방지합니다.
     */
    @AfterEach
    void tearDown() {
        if (seasonId != null) {
            // 하위 엔티티를 삭제한 뒤에만 그룹 시즌과 시즌을 정리합니다.
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
     * 두 활성 그룹을 처음 초기화한 뒤 재실행해도 INSERT가 반복되지 않습니다.
     * 시즌의 과거 startAt이 아니라 실제 Clock 시각부터 72시간을 보장합니다.
     * GroupSeason·알·0점 집계가 모두 두 건씩 저장되는지 확인합니다.
     */
    @Test
    void initialBatchCommitsAndIsIdempotent() {
        createScheduledSeason(START, END);
        Group first = createGroup();
        createGroup();

        SeasonGroupInitializationService.BatchResult firstResult = service.initializeNextBatch(seasonId);
        SeasonGroupInitializationService.BatchResult repeated = service.initializeNextBatch(seasonId);

        assertEquals(SeasonGroupInitializationService.Outcome.COMPLETE, firstResult.outcome());
        assertEquals(2, firstResult.createdGroupSeasons());
        assertEquals(2, firstResult.createdEggs());
        assertEquals(2, firstResult.createdScores());
        assertEquals(0, repeated.processedGroups());
        assertEquals(2L, count("group_seasons", "season_id"));
        assertEquals(2L, countChild("group_creatures"));
        assertEquals(2L, countChild("season_group_scores"));

        // 실제 DB의 Timestamp와 그룹 시즌의 선택 기한을 직접 확인합니다.
        GroupSeason stored = groupSeasonRepository
                .findSnapshotByGroupIdAndSeasonId(first.getId(), seasonId)
                .orElseThrow();
        assertEquals(NOW, stored.getCreatedAt());
        assertEquals(NOW.plus(Duration.ofHours(72)), stored.getSelectionDeadlineAt());
    }

    /**
     * 기존 GroupSeason과 알이 저장됐지만 점수 행이 빠진 상태를 복구합니다.
     * 재실행 시 72시간 기한을 다시 연장해서는 안 됩니다.
     * 이미 있던 알을 교체하거나 부화시키지 않고 점수만 보완합니다.
     */
    @Test
    void missingScoreIsRepairedWithoutResettingEggOrDeadline() {
        createScheduledSeason(START, END);
        Group group = createGroup();
        Instant original = NOW.minus(Duration.ofDays(2));
        GroupSeason partial = groupSeasonRepository.saveAndFlush(
                GroupSeason.start(group, seasonRepository.findById(seasonId).orElseThrow(), original));
        GroupCreature originalEgg = creatureRepository.saveAndFlush(GroupCreature.egg(partial, original));

        SeasonGroupInitializationService.BatchResult result = service.initializeNextBatch(seasonId);

        assertEquals(0, result.createdGroupSeasons());
        assertEquals(0, result.createdEggs());
        assertEquals(1, result.createdScores());
        GroupSeason stored = groupSeasonRepository.findById(partial.getId()).orElseThrow();
        GroupCreature egg = creatureRepository.findById(originalEgg.getId()).orElseThrow();
        assertEquals(original.plus(Duration.ofHours(72)), stored.getSelectionDeadlineAt());
        assertEquals(original, egg.getCreatedAt());
        assertEquals("EGG", egg.getGrowthStage());
        assertEquals(1L, countChild("season_group_scores"));
    }

    /**
     * 만료된 SCHEDULED 시즌에는 그룹·알·점수를 생성하지 않습니다.
     * 서버 중단 중 지나간 4주 구간은 소급 초기화 대상이 아닙니다.
     * 만료 뒤의 대상 그룹이 존재해도 DB 기록을 변경하지 않습니다.
     */
    @Test
    void expiredScheduledSeasonDoesNotInitialize() {
        Instant expiredStart = Instant.parse("2026-11-01T15:00:00Z");
        createScheduledSeason(expiredStart, expiredStart.plus(Duration.ofDays(28)));
        createGroup();

        SeasonGroupInitializationService.BatchResult result = service.initializeNextBatch(seasonId);

        assertEquals(SeasonGroupInitializationService.Outcome.EXPIRED, result.outcome());
        assertEquals(0L, count("group_seasons", "season_id"));
        assertEquals(0L, countChild("group_creatures"));
    }

    /**
     * 비활성 그룹은 신규 시즌 참여 대상으로 선정하지 않습니다.
     * 한 명의 관리자만 있는 그룹을 그룹 도메인 메서드로 종료합니다.
     * 활성 그룹만 초기화하는 조회 쿼리의 필터링을 검증합니다.
     */
    @Test
    void inactiveGroupIsExcluded() {
        createScheduledSeason(START, END);
        Group group = createGroup();
        group.deactivateAfterLastMemberLeaves(NOW);
        groupRepository.saveAndFlush(group);

        SeasonGroupInitializationService.BatchResult result = service.initializeNextBatch(seasonId);

        assertEquals(0, result.processedGroups());
        assertEquals(0L, count("group_seasons", "season_id"));
    }

    /**
     * 두 서버를 흉내내는 동시 호출에서도 그룹 시즌은 한 번만 생성합니다.
     * 같은 시즌의 PESSIMISTIC_WRITE가 두 트랜잭션을 직렬화해야 합니다.
     * 하나의 호출이 Commit한 뒤 다른 호출은 완료된 그룹을 제외해야 합니다.
     *
     * @throws Exception 병렬 작업 대기 오류
     */
    @Test
    void concurrentInitializationCreatesOneSetOfRows() throws Exception {
        createScheduledSeason(START, END);
        createGroup();
        CyclicBarrier barrier = new CyclicBarrier(2);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            // 각 Worker는 별도 스레드에서 독립 트랜잭션을 사용합니다.
            Future<SeasonGroupInitializationService.BatchResult> first = executor.submit(() -> {
                barrier.await(5, TimeUnit.SECONDS);
                return service.initializeNextBatch(seasonId);
            });
            Future<SeasonGroupInitializationService.BatchResult> second = executor.submit(() -> {
                barrier.await(5, TimeUnit.SECONDS);
                return service.initializeNextBatch(seasonId);
            });

            // 누가 먼저 선점했는지는 상관없이 총 INSERT 한 번이어야 합니다.
            int created = first.get(12, TimeUnit.SECONDS).createdGroupSeasons()
                    + second.get(12, TimeUnit.SECONDS).createdGroupSeasons();
            assertEquals(1, created);
            assertEquals(1L, count("group_seasons", "season_id"));
            assertEquals(1L, countChild("group_creatures"));
            assertEquals(1L, countChild("season_group_scores"));
        } finally {
            executor.shutdownNow();
        }
    }

    /**
     * 테스트 전용의 독립 SCHEDULED 시즌을 생성합니다.
     * 만료된 시즌에서도 팩토리 생성 시각만은 유효한 구간으로 지정합니다.
     * 테스트 실행 Clock은 별도로 고정되어 실제 처리 조건을 검사합니다.
     */
    private void createScheduledSeason(Instant start, Instant end) {
        int number = ThreadLocalRandom.current().nextInt(200_000_000, 900_000_000);
        Season season = Season.schedule(number, start, end, start.plus(Duration.ofDays(1)));
        seasonId = seasonRepository.saveAndFlush(season).getId();
    }

    /**
     * 이미 생성한 테스트 사용자를 관리자로 갖는 활성 그룹을 생성합니다.
     * 정규화 그룹명이 중복되지 않도록 고유 문자열을 사용합니다.
     * 멤버십이 필요하지 않은 초기화 서비스만 검증합니다.
     */
    private Group createGroup() {
        User admin = userRepository.findById(userId).orElseThrow();
        String suffix = Long.toString(System.nanoTime());
        Group group = groupRepository.saveAndFlush(
                Group.create("e2" + suffix, "e2" + suffix, "", admin, NOW));
        groupIds.add(group.getId());
        return group;
    }

    /**
     * 시즌 ID로 직접 연결된 테이블의 행 수를 JDBC로 조회합니다.
     * JPA 영속성 컨텍스트의 기존 캐시 대신 Commit된 DB를 읽습니다.
     * 입력 테이블과 컬럼은 테스트 코드 내부의 고정 문자열만 허용합니다.
     */
    private long count(String table, String seasonColumn) {
        Long result = jdbc.queryForObject(
                "SELECT COUNT(*) FROM " + table + " WHERE " + seasonColumn + " = ?",
                Long.class, seasonId);
        return Objects.requireNonNull(result);
    }

    /**
     * group_seasons를 통해 알 또는 점수의 저장 개수를 조회합니다.
     * 생성한 테스트 전용 시즌의 자식 행만 검사합니다.
     * 실제 DB 외래키와 테이블 이름을 기준으로 조회합니다.
     */
    private long countChild(String table) {
        Long result = jdbc.queryForObject("""
                SELECT COUNT(*)
                  FROM %s child
                  JOIN group_seasons gs ON gs.id = child.group_season_id
                 WHERE gs.season_id = ?
                """.formatted(table), Long.class, seasonId);
        return Objects.requireNonNull(result);
    }
}
