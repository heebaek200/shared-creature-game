package com.team.sharedcreature.season.integration;

import com.team.sharedcreature.auth.domain.User;
import com.team.sharedcreature.auth.infrastructure.UserRepository;
import com.team.sharedcreature.group.domain.Group;
import com.team.sharedcreature.group.infrastructure.GroupRepository;
import com.team.sharedcreature.season.domain.GroupSeason;
import com.team.sharedcreature.season.domain.Season;
import com.team.sharedcreature.season.infrastructure.GroupSeasonRepository;
import com.team.sharedcreature.season.infrastructure.SeasonRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.BrokenBarrierException;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * 같은 시즌의 공유 잠금을 보유한 서로 다른 그룹 트랜잭션이
 * GroupSeasonRepository의 배타 잠금 조회에서 교착되지 않는지 검증합니다.
 * 실제 MySQL/InnoDB와 Hibernate가 생성한 SQL을 사용합니다.
 */
@SpringBootTest
@ActiveProfiles("test")
@TestPropertySource(properties = "logging.level.org.hibernate.SQL=DEBUG")
@EnabledIfEnvironmentVariable(named = "TEST_DB_USERNAME", matches = ".+")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class GroupSeasonLockConcurrencyIntegrationTest {

    private static final Instant START = Instant.parse("2026-11-01T15:00:00Z");
    private static final Instant END = START.plus(Duration.ofDays(28));
    private static final Instant NOW = START.plus(Duration.ofDays(1));

    @Autowired private UserRepository userRepository;
    @Autowired private GroupRepository groupRepository;
    @Autowired private SeasonRepository seasonRepository;
    @Autowired private GroupSeasonRepository groupSeasonRepository;
    @Autowired private PlatformTransactionManager transactionManager;
    @Autowired private JdbcTemplate jdbc;

    private Long seasonId;
    private Long ownerAId;
    private Long ownerBId;
    private Long groupAId;
    private Long groupBId;

    /**
     * 실제 JPA 엔티티를 이용해 ACTIVE 시즌과 서로 다른 두 그룹 시즌을 생성합니다.
     * 테스트용 MySQL에는 다른 시즌이 없어야 재현 대상이 명확합니다.
     * 모든 INSERT는 worker 트랜잭션 시작 전에 Commit됩니다.
     */
    @BeforeEach
    void setUp() {
        // 이전 테스트의 데이터와 뒤섞이지 않도록 전용 test DB만 사용합니다.
        assertEquals(0L, jdbc.queryForObject("SELECT COUNT(*) FROM seasons", Long.class));

        Season season = Season.schedule(1, START, END, NOW);
        assertTrue(season.activateAfterInitialization(NOW));
        season = seasonRepository.saveAndFlush(season);
        seasonId = season.getId();

        // 각 트랜잭션은 서로 다른 사용자·그룹·그룹 시즌을 잠글 예정입니다.
        User ownerA = userRepository.saveAndFlush(User.register("USER", NOW));
        ownerAId = ownerA.getId();
        User ownerB = userRepository.saveAndFlush(User.register("USER", NOW));
        ownerBId = ownerB.getId();

        Group groupA = groupRepository.saveAndFlush(Group.create(
                "잠금테스트-A", "lock-test-a-" + ownerAId, "", ownerA, NOW));
        groupAId = groupA.getId();
        Group groupB = groupRepository.saveAndFlush(Group.create(
                "잠금테스트-B", "lock-test-b-" + ownerBId, "", ownerB, NOW));
        groupBId = groupB.getId();

        groupSeasonRepository.saveAndFlush(GroupSeason.start(groupA, season, NOW));
        groupSeasonRepository.saveAndFlush(GroupSeason.start(groupB, season, NOW));
    }

    /**
     * 재현에 필요한 시즌·그룹·사용자만 외래키 역순으로 삭제합니다.
     * 별도 Commit된 worker 작업이 모두 끝난 다음 호출됩니다.
     * 실패한 테스트가 다음 통합 테스트의 데이터에 영향을 주지 않도록 합니다.
     */
    @AfterEach
    void tearDown() {
        if (seasonId != null) {
            jdbc.update("DELETE FROM group_seasons WHERE season_id = ?", seasonId);
        }
        if (groupAId != null) jdbc.update("DELETE FROM game_groups WHERE id = ?", groupAId);
        if (groupBId != null) jdbc.update("DELETE FROM game_groups WHERE id = ?", groupBId);
        if (ownerAId != null) jdbc.update("DELETE FROM users WHERE id = ?", ownerAId);
        if (ownerBId != null) jdbc.update("DELETE FROM users WHERE id = ?", ownerBId);
        if (seasonId != null) jdbc.update("DELETE FROM seasons WHERE id = ?", seasonId);
    }

    /**
     * 두 트랜잭션이 같은 시즌 공유 잠금을 획득한 상태에서 시작하도록 강제합니다.
     * 각 스레드는 서로 다른 그룹 시즌 행에 PESSIMISTIC_WRITE를 요청합니다.
     * 하나라도 데드락·잠금 시간 초과가 발생하면 테스트를 실패시킵니다.
     */
    @Test
    void sharedSeasonLockMustNotDeadlockWhenDifferentGroupSeasonsAreWriteLocked() throws InterruptedException {
        CyclicBarrier bothHoldingSeasonShareLock = new CyclicBarrier(2);
        ExecutorService workers = Executors.newFixedThreadPool(2);

        try {
            // 각 작업은 독립적인 물리 트랜잭션·DB 커넥션에서 실행됩니다.
            Future<?> first = workers.submit(() -> lockOneGroupSeason(groupAId, bothHoldingSeasonShareLock));
            Future<?> second = workers.submit(() -> lockOneGroupSeason(groupBId, bothHoldingSeasonShareLock));

            assertAll(
                    () -> assertCompletes(first, "groupA"),
                    () -> assertCompletes(second, "groupB")
            );

            // 쓰기 잠금 쿼리는 조회만 하므로 그룹 시즌 상태는 유지되어야 합니다.
            assertEquals(2L, jdbc.queryForObject("""
                    SELECT COUNT(*)
                      FROM group_seasons
                     WHERE season_id = ?
                       AND status = 'PREPARING'
                    """, Long.class, seasonId));
        } finally {
            // 장애 발생 시 대기 중인 배리어를 해제하고 worker를 종료합니다.
            bothHoldingSeasonShareLock.reset();
            workers.shutdownNow();
            assertTrue(workers.awaitTermination(10, TimeUnit.SECONDS),
                    "DB 작업이 종료되지 않았습니다. InnoDB lock wait 상태를 확인하세요.");
        }
    }

    /**
     * 시즌 행에 공유 잠금을 먼저 걸고 두 작업이 모두 도달할 때까지 기다립니다.
     * 두 작업이 같은 시즌 공유 잠금을 가진 채 서로 다른 그룹 시즌에 쓰기 잠금을 요청합니다.
     * 실제 Repository JPQL의 JOIN·FOR UPDATE 잠금 범위를 검증하는 핵심 재현 지점입니다.
     */
    private void lockOneGroupSeason(Long groupId, CyclicBarrier barrier) {
        TransactionTemplate tx = new TransactionTemplate(transactionManager);

        tx.executeWithoutResult(status -> {
            // SeasonQueryService가 사용하는 것과 동일한 시즌 공유 잠금 쿼리입니다.
            List<Season> active = seasonRepository.findForShareByStatus(
                    "ACTIVE", PageRequest.of(0, 1));
            assertEquals(1, active.size());
            assertEquals(seasonId, active.getFirst().getId());

            // 두 트랜잭션이 동시에 공유 잠금을 보유해야 데드락 가능성을 검증할 수 있습니다.
            try {
                barrier.await(10, TimeUnit.SECONDS);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("동시성 테스트가 중단됐습니다.", interrupted);
            } catch (BrokenBarrierException | TimeoutException exception) {
                throw new IllegalStateException("두 트랜잭션이 공유 잠금 지점에 도달하지 못했습니다.", exception);
            }

            // 실제 의심 메서드를 호출합니다. 그룹은 다르지만 season은 같습니다.
            Optional<GroupSeason> result = groupSeasonRepository
                    .findOpenByGroupIdForUpdate(groupId, NOW);
            assertTrue(result.isPresent(), "그룹 시즌을 찾지 못했습니다. groupId=" + groupId);
            assertEquals(groupId, result.orElseThrow().getGroup().getId());
        });
    }

    /**
     * worker 예외를 단순 timeout으로 뭉개지 않고 원인 예외와 함께 노출합니다.
     * InnoDB 데드락·비관적 잠금 예외가 발생하면 해당 메시지를 확인할 수 있습니다.
     * 트랜잭션이 정상 Commit되어야 통과로 판단합니다.
     */
    private void assertCompletes(Future<?> future, String groupName) {
        try {
            future.get(20, TimeUnit.SECONDS);
        } catch (ExecutionException exception) {
            fail(groupName + " 트랜잭션 실패: " + exception.getCause(), exception.getCause());
        } catch (TimeoutException exception) {
            fail(groupName + " 트랜잭션이 20초 내 종료되지 않았습니다.", exception);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            fail(groupName + " 트랜잭션 대기 중 인터럽트가 발생했습니다.", exception);
        }
    }
}
