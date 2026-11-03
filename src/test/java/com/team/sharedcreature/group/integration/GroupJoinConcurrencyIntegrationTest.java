package com.team.sharedcreature.group.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.team.sharedcreature._core.exception.BusinessException;
import com.team.sharedcreature._core.security.LoginUser;
import com.team.sharedcreature.group.application.GroupMembershipService;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.sql.Statement;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.test.context.ActiveProfiles;

/** 실제 MySQL 잠금으로 그룹 정원과 사용자당 단일 활성 멤버십을 검증합니다. */
@SpringBootTest
@ActiveProfiles("test")
@EnabledIfEnvironmentVariable(named = "TEST_DB_USERNAME", matches = ".+")
class GroupJoinConcurrencyIntegrationTest {

    private static final String NAME_PREFIX = "동시가입검증";

    @Autowired GroupMembershipService membershipService;
    @Autowired JdbcTemplate jdbcTemplate;

    private ExecutorService executor;
    private final List<Long> fixtureUserIds = new ArrayList<>();

    @BeforeEach
    void setUp() {
        executor = Executors.newFixedThreadPool(2);
        deleteFixtures();
        fixtureUserIds.clear();
    }

    @AfterEach
    void tearDown() {
        deleteFixtures();
        executor.shutdownNow();
    }

    @Test
    void 열아홉명_그룹에_두명이_동시_가입하면_한명만_성공한다() throws Exception {
        Long firstUserId = insertUser();
        Long secondUserId = insertUser();
        Long groupId = insertGroup(NAME_PREFIX + "정원", 19);
        for (int index = 0; index < 19; index++) {
            insertMembership(groupId, insertUser(), index == 0 ? "PRIMARY" : "MEMBER");
        }
        CountDownLatch start = new CountDownLatch(1);

        Future<String> first = submitJoin(firstUserId, groupId, start);
        Future<String> second = submitJoin(secondUserId, groupId, start);
        start.countDown();

        List<String> results = List.of(first.get(5, TimeUnit.SECONDS), second.get(5, TimeUnit.SECONDS));
        assertThat(results).containsExactlyInAnyOrder("SUCCESS", "GROUP_FULL");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT current_member_count FROM game_groups WHERE id = ?", Integer.class, groupId
        )).isEqualTo(20);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM group_memberships WHERE group_id = ? AND status = 'ACTIVE'",
                Integer.class,
                groupId
        )).isEqualTo(20);
    }

    @Test
    void 한_사용자가_서로_다른_그룹에_동시_가입하면_한_건만_활성화된다() throws Exception {
        Long userId = insertUser();
        Long firstGroupId = insertGroup(NAME_PREFIX + "가", 0);
        Long secondGroupId = insertGroup(NAME_PREFIX + "나", 0);
        CountDownLatch start = new CountDownLatch(1);

        Future<String> first = submitJoin(userId, firstGroupId, start);
        Future<String> second = submitJoin(userId, secondGroupId, start);
        start.countDown();

        List<String> results = List.of(first.get(5, TimeUnit.SECONDS), second.get(5, TimeUnit.SECONDS));
        assertThat(results).containsExactlyInAnyOrder("SUCCESS", "ALREADY_IN_GROUP");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM group_memberships WHERE user_id = ? AND status = 'ACTIVE'",
                Integer.class,
                userId
        )).isEqualTo(1);
    }

    private Future<String> submitJoin(Long userId, Long groupId, CountDownLatch start) {
        return executor.submit(() -> {
            await(start);
            try {
                membershipService.joinGroup(new LoginUser(userId, "가입자", Set.of()), groupId);
                return "SUCCESS";
            } catch (BusinessException exception) {
                return exception.code();
            }
        });
    }

    private Long insertUser() {
        String sql = """
                INSERT INTO users (
                    status
                  , global_role
                  , last_login_at
                  , last_active_at
                  , version
                  , created_at
                  , updated_at
                ) VALUES ('ACTIVE', 'USER', UTC_TIMESTAMP(6), UTC_TIMESTAMP(6), 0, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
                """;
        GeneratedKeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(connection -> connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS), keyHolder);
        Long userId = keyHolder.getKey().longValue();
        fixtureUserIds.add(userId);
        return userId;
    }

    private Long insertGroup(String name, int memberCount) {
        String sql = """
                INSERT INTO game_groups (
                    name
                  , normalized_name
                  , description
                  , visibility
                  , status
                  , capacity
                  , current_member_count
                  , primary_admin_user_id
                  , version
                  , created_at
                  , updated_at
                ) VALUES (?, ?, '', 'PUBLIC', 'ACTIVE', 20, ?, NULL, 0, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
                """;
        GeneratedKeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(connection -> {
            var statement = connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS);
            statement.setString(1, name);
            statement.setString(2, name);
            statement.setInt(3, memberCount);
            return statement;
        }, keyHolder);
        return keyHolder.getKey().longValue();
    }

    private void insertMembership(Long groupId, Long userId, String role) {
        jdbcTemplate.update(
                """
                INSERT INTO group_memberships (
                    group_id
                  , user_id
                  , role
                  , status
                  , joined_at
                  , version
                  , created_at
                  , updated_at
                ) VALUES (?, ?, ?, 'ACTIVE', UTC_TIMESTAMP(6), 0, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
                """,
                groupId,
                userId,
                role
        );
    }

    private void deleteFixtures() {
        jdbcTemplate.update(
                """
                DELETE membership
                  FROM group_memberships membership
                  JOIN game_groups game_group ON game_group.id = membership.group_id
                 WHERE game_group.name LIKE ?
                """,
                NAME_PREFIX + "%"
        );
        jdbcTemplate.update("DELETE FROM game_groups WHERE name LIKE ?", NAME_PREFIX + "%");
        fixtureUserIds.forEach(userId -> jdbcTemplate.update("DELETE FROM users WHERE id = ?", userId));
    }

    private void await(CountDownLatch latch) {
        try {
            if (!latch.await(3, TimeUnit.SECONDS)) {
                throw new IllegalStateException("Timed out while coordinating group join test.");
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Group join test was interrupted.", exception);
        }
    }
}
