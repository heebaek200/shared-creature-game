package com.team.sharedcreature.group.integration;

import com.team.sharedcreature._core.security.Hashing;
import com.team.sharedcreature.group.api.RotateInvitationResponse;
import com.team.sharedcreature.group.application.GroupInvitationManagementService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.test.context.ActiveProfiles;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * STEP 6 초대 재발급의 실제 DB 동시성 경합을 검증합니다.
 *
 * 테스트 본문은 @Transactional로 감싸지 않아 서로 다른 스레드에서 데이터가 보입니다.
 * 각 Service 요청은 자체 @Transactional을 통해 별개 연결로 실행됩니다.
 * 테스트가 끝나면 전용 Fixture를 명시적으로 삭제합니다.
 */
@SpringBootTest
@ActiveProfiles("test")
@EnabledIfEnvironmentVariable(named = "TEST_DB_USERNAME", matches = ".+")
class GroupInvitationRotationConcurrencyIntegrationTest {

    @Autowired private GroupInvitationManagementService service;
    @Autowired private JdbcTemplate jdbcTemplate;

    private long userId;
    private long groupId;

    /**
     * 테스트 전용 PRIMARY, 그룹, 멤버십, 활성 초대 링크를 생성합니다.
     *
     * 별도 스레드의 트랜잭션이 읽을 수 있도록 INSERT는 테스트 시작 전에 Commit됩니다.
     * 그룹 이름은 UUID를 사용해 기존 테스트 데이터와 충돌하지 않습니다.
     */
    @BeforeEach
    void setUp() {
        userId = insert("""
                INSERT INTO users (
                    status
                  , global_role
                  , last_login_at
                  , last_active_at
                  , version
                  , created_at
                  , updated_at
                ) VALUES ('ACTIVE', 'USER', UTC_TIMESTAMP(6), UTC_TIMESTAMP(6),
                          0, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
                """);
        String name = "동시회전" + UUID.randomUUID().toString().substring(0, 12);

        // 그룹과 PRIMARY 멤버십은 함께 생성하고 별도 SQL로 Commit됩니다.
        groupId = insert("""
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
                ) VALUES (?, ?, '', 'PUBLIC', 'ACTIVE', 20, 1, ?,
                          0, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
                """, name, name.toLowerCase(Locale.ROOT), userId);
        insert("""
                INSERT INTO group_memberships (
                    group_id
                  , user_id
                  , role
                  , status
                  , joined_at
                  , version
                  , created_at
                  , updated_at
                ) VALUES (?, ?, 'PRIMARY', 'ACTIVE', UTC_TIMESTAMP(6),
                          0, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
                """, groupId, userId);

        // 토큰 해시만 저장하는 최초 활성 링크를 추가합니다.
        insert("""
                INSERT INTO group_invite_links (
                    group_id
                  , token_hash
                  , status
                  , issued_by_user_id
                  , issued_at
                  , created_at
                ) VALUES (?, ?, 'ACTIVE', ?, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
                """, groupId, Hashing.sha256(UUID.randomUUID().toString()), userId);
    }

    /**
     * 전용 Fixture 및 재발급된 모든 링크를 삭제합니다.
     *
     * FK 제약을 지키기 위해 초대 링크, 멤버십, 그룹, 사용자 순서로 삭제합니다.
     * 생성한 ID만 사용하므로 기존 로컬 데이터를 건드리지 않습니다.
     */
    @AfterEach
    void tearDown() {
        if (groupId > 0) {
            jdbcTemplate.update("DELETE FROM group_invite_links WHERE group_id = ?", groupId);
            jdbcTemplate.update("DELETE FROM group_memberships WHERE group_id = ?", groupId);
            jdbcTemplate.update("DELETE FROM game_groups WHERE id = ?", groupId);
        }
        if (userId > 0) {
            jdbcTemplate.update("DELETE FROM users WHERE id = ?", userId);
        }
    }

    /**
     * 같은 PRIMARY가 두 번 동시에 재발급하더라도 활성 링크는 한 개만 남습니다.
     *
     * Service 트랜잭션은 user → group → invitation 잠금에 의해 직렬화됩니다.
     * 두 응답이 모두 성공하면 두 개의 링크 이력은 보존되고 최신 것만 활성입니다.
     * 이 테스트는 실제 InnoDB 행 잠금과 생성 컬럼 UNIQUE를 함께 통과해야 합니다.
     */
    @Test
    void 동시_두_번_재발급해도_활성_링크는_한_건이다() throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);

        try {
            // 두 트랜잭션이 가능한 한 비슷한 시각에 시작하도록 맞춥니다.
            Future<RotateInvitationResponse> first = executor.submit(() -> {
                if (!start.await(5, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("첫 번째 동시 실행 대기 초과");
                }
                return service.rotateInvitation(userId);
            });
            Future<RotateInvitationResponse> second = executor.submit(() -> {
                if (!start.await(5, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("두 번째 동시 실행 대기 초과");
                }
                return service.rotateInvitation(userId);
            });
            start.countDown();

            // Service는 두 요청을 차례로 Commit한 뒤 서로 다른 URL을 반환합니다.
            var firstResult = first.get(15, TimeUnit.SECONDS);
            var secondResult = second.get(15, TimeUnit.SECONDS);
            assertThat(firstResult.invitationUrl()).isNotEqualTo(secondResult.invitationUrl());

            // 총 3건(기존 1 + 신규 2) 중 마지막 1건만 ACTIVE입니다.
            Integer active = jdbcTemplate.queryForObject("""
                    SELECT COUNT(*)
                      FROM group_invite_links
                     WHERE group_id = ?
                       AND status = 'ACTIVE'
                    """, Integer.class, groupId);
            Integer invalidated = jdbcTemplate.queryForObject("""
                    SELECT COUNT(*)
                      FROM group_invite_links
                     WHERE group_id = ?
                       AND status = 'INVALIDATED'
                    """, Integer.class, groupId);

            assertThat(active).isEqualTo(1);
            assertThat(invalidated).isEqualTo(2);

            // 유효한 링크의 해시는 두 응답 중 하나에서 발행한 원문과 일치해야 합니다.
            String activeHash = jdbcTemplate.queryForObject("""
                    SELECT token_hash
                      FROM group_invite_links
                     WHERE group_id = ?
                       AND status = 'ACTIVE'
                    """, String.class, groupId);
            assertThat(activeHash).isIn(
                    Hashing.sha256(tokenFrom(firstResult.invitationUrl())),
                    Hashing.sha256(tokenFrom(secondResult.invitationUrl()))
            );
        } finally {
            executor.shutdownNow();
            executor.awaitTermination(5, TimeUnit.SECONDS);
        }
    }

    /**
     * 발행된 초대 URL에서 토큰 문자열만 추출합니다.
     *
     * 앱 Origin이 달라져도 마지막 경로 요소만 사용합니다.
     * 검증 목적 이외의 저장이나 로그 출력에는 토큰을 사용하지 않습니다.
     */
    private String tokenFrom(String url) {
        return url.substring(url.lastIndexOf('/') + 1);
    }

    /**
     * PreparedStatement로 INSERT하고 MySQL 자동 증가 PK를 반환합니다.
     *
     * Fixture의 사용자·그룹·멤버십을 단계별로 연결합니다.
     * 테스트는 @Transactional 없이 각 INSERT 후 Commit된 상태를 만듭니다.
     */
    private long insert(String sql, Object... values) {
        GeneratedKeyHolder holder = new GeneratedKeyHolder();
        jdbcTemplate.update(connection -> {
            PreparedStatement statement = connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS);
            for (int i = 0; i < values.length; i++) {
                statement.setObject(i + 1, values[i]);
            }
            return statement;
        }, holder);
        return Objects.requireNonNull(holder.getKey()).longValue();
    }
}
