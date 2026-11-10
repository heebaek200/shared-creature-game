package com.team.sharedcreature.group.integration;

import com.team.sharedcreature._core.security.Hashing;
import com.team.sharedcreature.group.application.GroupMembershipService;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 마지막 PRIMARY 탈퇴 시 실제 시즌과 크리처 종료를 MySQL에서 검증합니다.
 *
 * 테스트의 단일 트랜잭션 안에서 Fixture를 만들고 서비스도 호출합니다.
 * JPA 변경 사항은 flush 후 JDBC 조회로 검증하며 테스트 종료 시 롤백합니다.
 * 별도 스레드를 사용하지 않으므로 동시성 테스트와 책임이 겹치지 않습니다.
 */
@SpringBootTest
@ActiveProfiles("test")
@EnabledIfEnvironmentVariable(named = "TEST_DB_USERNAME", matches = ".+")
class GroupLastPrimarySeasonClosureIntegrationTest {

    @Autowired private GroupMembershipService membershipService;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private EntityManager entityManager;

    /**
     * 유일한 PRIMARY 탈퇴가 그룹·시즌·크리처·초대 링크를 함께 종료하는지 검증합니다.
     *
     * PREPARING 그룹 시즌과 ACTIVE 알 크리처를 실제 DB에 생성합니다.
     * 종료된 상태를 직접 SQL로 검증하고 경험치 및 성장 단계 보존도 확인합니다.
     * 이 테스트의 모든 INSERT와 UPDATE는 테스트 종료 시 자동으로 롤백됩니다.
     */
    @Test
    @Transactional
    void 마지막_PRIMARY_탈퇴는_시즌과_크리처를_보관하고_이력을_보존한다() {
        // 1. 단독 PRIMARY 사용자와 1인 그룹을 만듭니다.
        long userId = insertId("""
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
        String name = "시즌종료" + UUID.randomUUID().toString().substring(0, 12);
        long groupId = insertId("""
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
                ) VALUES (?, ?, '', 'PUBLIC', 'ACTIVE', 20, 1, ?, 0,
                          UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
                """, name, name.toLowerCase(Locale.ROOT), userId);

        // 2. 그룹에 활성 PRIMARY 멤버십 한 건만 추가합니다.
        insertId("""
                INSERT INTO group_memberships (
                    group_id
                  , user_id
                  , role
                  , status
                  , joined_at
                  , version
                  , created_at
                  , updated_at
                ) VALUES (?, ?, 'PRIMARY', 'ACTIVE', UTC_TIMESTAMP(6), 0,
                          UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
                """, groupId, userId);

        // 3. 테스트 전용 시즌과 준비 중인 그룹 시즌 참여를 생성합니다.
        int seasonNumber = ThreadLocalRandom.current()
                .nextInt(1_000_000_000, Integer.MAX_VALUE);
        long seasonId = insertId("""
                INSERT INTO seasons (
                    season_number
                  , start_at
                  , end_at
                  , status
                  , version
                  , created_at
                  , updated_at
                ) VALUES (?, DATE_SUB(UTC_TIMESTAMP(6), INTERVAL 1 DAY),
                          DATE_ADD(UTC_TIMESTAMP(6), INTERVAL 30 DAY),
                          'ACTIVE', 0, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
                """, seasonNumber);
        long groupSeasonId = insertId("""
                INSERT INTO group_seasons (
                    group_id
                  , season_id
                  , selection_deadline_at
                  , status
                  , version
                  , created_at
                  , updated_at
                ) VALUES (?, ?, DATE_ADD(UTC_TIMESTAMP(6), INTERVAL 72 HOUR),
                          'PREPARING', 0, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
                """, groupId, seasonId);

        // 4. 실제 알 크리처와 초대 링크를 연결합니다.
        long creatureId = insertId("""
                INSERT INTO group_creatures (
                    group_season_id
                  , total_experience
                  , growth_stage
                  , status
                  , version
                  , created_at
                  , updated_at
                ) VALUES (?, 1234, 'EGG', 'ACTIVE', 0,
                          UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
                """, groupSeasonId);
        long inviteId = insertId("""
                INSERT INTO group_invite_links (
                    group_id
                  , token_hash
                  , status
                  , issued_by_user_id
                  , issued_at
                  , created_at
                ) VALUES (?, ?, 'ACTIVE', ?, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
                """, groupId, Hashing.sha256("last-primary-" + UUID.randomUUID()), userId);

        // 5. 실제 Service가 모든 종료 상태를 처리하도록 호출합니다.
        var result = membershipService.leaveMyGroup(userId);
        entityManager.flush();

        // 6. 상태 전환뿐 아니라 기존 성장 이력이 보존되는지 DB에서 검사합니다.
        assertThat(result.groupDeactivated()).isTrue();
        assertThat(value("SELECT status FROM game_groups WHERE id = ?", String.class, groupId))
                .isEqualTo("INACTIVE");
        assertThat(value("SELECT current_member_count FROM game_groups WHERE id = ?", Integer.class, groupId))
                .isZero();
        assertThat(value("SELECT primary_admin_user_id FROM game_groups WHERE id = ?", Long.class, groupId))
                .isNull();
        assertThat(value("SELECT status FROM group_memberships WHERE group_id = ?", String.class, groupId))
                .isEqualTo("LEFT");
        assertThat(value("SELECT status FROM group_invite_links WHERE id = ?", String.class, inviteId))
                .isEqualTo("INVALIDATED");
        assertThat(value("SELECT status FROM group_seasons WHERE id = ?", String.class, groupSeasonId))
                .isEqualTo("ENDED");
        assertThat(value("SELECT status FROM group_creatures WHERE id = ?", String.class, creatureId))
                .isEqualTo("ARCHIVED");
        assertThat(value("SELECT total_experience FROM group_creatures WHERE id = ?", Long.class, creatureId))
                .isEqualTo(1234L);
        assertThat(value("SELECT growth_stage FROM group_creatures WHERE id = ?", String.class, creatureId))
                .isEqualTo("EGG");
        assertThat(value("SELECT COUNT(*) FROM group_join_restrictions WHERE user_id = ?", Integer.class, userId))
                .isEqualTo(1);
    }

    /**
     * 테스트 데이터 INSERT 후 자동 생성된 PK를 반환합니다.
     *
     * 입력값은 PreparedStatement로 바인딩하여 SQL을 안전하게 실행합니다.
     * 테스트 메서드의 트랜잭션을 그대로 사용하므로 별도 Commit은 하지 않습니다.
     *
     * @param sql INSERT SQL
     * @param params 바인딩할 값
     * @return 생성된 PK
     */
    private long insertId(String sql, Object... params) {
        GeneratedKeyHolder holder = new GeneratedKeyHolder();

        // Statement에서 생성된 ID를 수집합니다.
        jdbc.update(connection -> {
            PreparedStatement statement = connection.prepareStatement(
                    sql, Statement.RETURN_GENERATED_KEYS);
            for (int i = 0; i < params.length; i++) {
                // JDBC가 숫자와 문자열 타입을 적절하게 바인딩합니다.
                statement.setObject(i + 1, params[i]);
            }
            return statement;
        }, holder);
        return Objects.requireNonNull(holder.getKey()).longValue();
    }

    /**
     * 실제 DB에 반영된 한 개의 결과값을 조회합니다.
     *
     * JPA 영속성 컨텍스트의 캐시가 아닌 SQL 조회 결과로 검증합니다.
     * 요청한 PK는 하나의 행을 식별해야 하며 값은 null일 수도 있습니다.
     *
     * @param sql 조회 SQL
     * @param type 기대 반환 타입
     * @param id 조회할 PK
     * @return 조회된 컬럼 값
     */
    private <T> T value(String sql, Class<T> type, long id) {
        // JdbcTemplate을 통해 실제 데이터베이스 컬럼을 검사합니다.
        return jdbc.queryForObject(sql, type, id);
    }
}
