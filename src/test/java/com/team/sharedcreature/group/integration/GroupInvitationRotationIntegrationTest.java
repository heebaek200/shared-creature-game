package com.team.sharedcreature.group.integration;

import com.team.sharedcreature._core.exception.BusinessException;
import com.team.sharedcreature._core.security.Hashing;
import com.team.sharedcreature.group.application.GroupInvitationManagementService;
import com.team.sharedcreature.group.application.GroupInvitationService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * STEP 6 초대 링크 회전과 무효화를 실제 MySQL에서 검증합니다.
 *
 * 현재 프로젝트의 test 프로필 및 MySQL 특수 생성 컬럼을 그대로 사용합니다.
 * 테스트별 그룹·회원·링크를 별도로 생성하고 트랜잭션으로 롤백합니다.
 * 이전 링크의 공개 API 차단과 활성 링크 UNIQUE 제약을 검증합니다.
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
@EnabledIfEnvironmentVariable(named = "TEST_DB_USERNAME", matches = ".+")
class GroupInvitationRotationIntegrationTest {

    private static final String INITIAL_TOKEN = "A".repeat(43);

    @Autowired private GroupInvitationManagementService managementService;
    @Autowired private GroupInvitationService invitationService;
    @Autowired private JdbcTemplate jdbcTemplate;

    /**
     * 연속 재발급이 실제 MySQL의 활성 링크 제약을 지키는지 검사합니다.
     *
     * 초기 링크, 1차 재발급 링크, 2차 재발급 링크를 모두 확인합니다.
     * 두 번 회전한 후 마지막 링크만 ACTIVE여야 합니다.
     * 기존 링크는 공개 초대 조회에서도 INVITE_INVALID로 거부해야 합니다.
     */
    @Test
    void 초대_연속_재발급은_실제_DB에_ACTIVE_한_건만_유지한다() {
        Fixture fixture = createFixture();
        long groupId = fixture.groupId();

        // 최초 링크가 활성 상태인 것을 확인한 뒤 회전합니다.
        assertThat(activeCount(groupId)).isEqualTo(1);
        var first = managementService.rotateInvitation(fixture.userId());
        assertThat(activeCount(groupId)).isEqualTo(1);

        // 첫 번째 신규 링크도 다시 회전하여 변경 순서를 검증합니다.
        var second = managementService.rotateInvitation(fixture.userId());
        assertThat(activeCount(groupId)).isEqualTo(1);
        assertThat(totalCount(groupId)).isEqualTo(3);

        String firstToken = tokenFrom(first.invitationUrl());
        String secondToken = tokenFrom(second.invitationUrl());
        assertThat(firstToken).isNotEqualTo(secondToken);

        // DB 저장값은 원문이 아닌 SHA-256 해시여야 합니다.
        assertThat(activeHash(groupId)).isEqualTo(Hashing.sha256(secondToken));
        assertThat(statusForHash(Hashing.sha256(INITIAL_TOKEN))).isEqualTo("INVALIDATED");
        assertThat(statusForHash(Hashing.sha256(firstToken))).isEqualTo("INVALIDATED");
        assertThat(statusForHash(Hashing.sha256(secondToken))).isEqualTo("ACTIVE");
        assertThat(invalidatedAtForHash(Hashing.sha256(firstToken))).isNotNull();

        // 폐기 링크는 공개 GET API에서 더 이상 사용될 수 없습니다.
        assertThatThrownBy(() -> invitationService.getPublicInvitation(
                firstToken, new MockHttpSession()
        )).isInstanceOfSatisfying(BusinessException.class,
                exception -> assertThat(exception.code()).isEqualTo("INVITE_INVALID"));

        // 마지막 링크만 정상적으로 공개 초대 정보를 반환합니다.
        var publicInvitation = invitationService.getPublicInvitation(
                secondToken, new MockHttpSession()
        );
        assertThat(publicInvitation.groupId()).isEqualTo(groupId);
    }

    /**
     * PRIMARY 관리 조회에서 현재 링크의 발급 이력을 반환하는지 검사합니다.
     *
     * 아직 재발급하지 않은 초기 링크의 발급자 정보와 GROUP ID를 검증합니다.
     * 읽기 전용 요청은 활성 링크 개수를 변경하지 않아야 합니다.
     */
    @Test
    void 관리_조회는_실제_DB의_발급자와_발급시각을_반환한다() {
        Fixture fixture = createFixture();

        // 응답은 기존 원문 토큰이 아니라 메타데이터만 제공합니다.
        var result = managementService.getInvitationManagement(fixture.userId());
        assertThat(result.groupId()).isEqualTo(fixture.groupId());
        assertThat(result.issuedByUserId()).isEqualTo(fixture.userId());
        assertThat(result.issuedAt()).isNotNull();
        assertThat(result.unlimitedUses()).isTrue();
        assertThat(result.expiresAt()).isNull();
        assertThat(activeCount(fixture.groupId())).isEqualTo(1);
    }

    /**
     * 각 테스트에 독립적인 사용자·그룹·활성 멤버십·초대 링크를 구성합니다.
     *
     * 실제 JPA 엔티티 컬럼과 FK 관계를 충족하는 최소 INSERT를 사용합니다.
     * MySQL의 생성 컬럼(active_group_id)은 직접 INSERT하지 않습니다.
     * 테스트의 @Transactional이 종료되면 모든 생성 데이터가 롤백됩니다.
     */
    private Fixture createFixture() {
        long userId = insertAndReturnId("""
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
        String name = "초대검증" + UUID.randomUUID().toString().substring(0, 10);

        // 그룹의 주 관리자와 첫 번째 멤버십을 생성합니다.
        long groupId = insertAndReturnId("""
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

        insertAndReturnId("""
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

        // 원문 토큰 대신 해시만 보관합니다.
        insertAndReturnId("""
                INSERT INTO group_invite_links (
                    group_id
                  , token_hash
                  , status
                  , issued_by_user_id
                  , issued_at
                  , created_at
                ) VALUES (?, ?, 'ACTIVE', ?, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
                """, groupId, Hashing.sha256(INITIAL_TOKEN), userId);

        return new Fixture(userId, groupId);
    }

    /**
     * JDBC INSERT 후 자동 생성된 PK를 반환합니다.
     *
     * SQL 값은 PreparedStatement로 바인딩합니다.
     * 저장소를 거치지 않고 실제 MySQL의 UNIQUE 및 FK 제약을 사용합니다.
     */
    private long insertAndReturnId(String sql, Object... values) {
        GeneratedKeyHolder holder = new GeneratedKeyHolder();

        // 테스트 트랜잭션과 동일한 연결을 사용합니다.
        jdbcTemplate.update(connection -> {
            PreparedStatement statement = connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS);
            for (int index = 0; index < values.length; index++) {
                statement.setObject(index + 1, values[index]);
            }
            return statement;
        }, holder);

        return Objects.requireNonNull(holder.getKey()).longValue();
    }

    /**
     * 신규 초대 URL의 마지막 경로 요소에서 원문 토큰을 추출합니다.
     *
     * 발급 주소는 앱 Origin과 /invite/{token} 형식을 따릅니다.
     * 저장하는 값은 SHA-256 해시이며 원문은 검증에서만 사용합니다.
     */
    private String tokenFrom(String url) {
        return url.substring(url.lastIndexOf('/') + 1);
    }

    /**
     * 생성 컬럼의 UNIQUE 제약 아래에서 실제 활성 링크 수를 계산합니다.
     *
     * 회전 직후 한 개만 남는지를 반복 확인합니다.
     * 과거 INVALIDATED 링크는 계산하지 않습니다.
     */
    private int activeCount(long groupId) {
        return Objects.requireNonNull(jdbcTemplate.queryForObject("""
                SELECT COUNT(*)
                  FROM group_invite_links
                 WHERE group_id = ?
                   AND status = 'ACTIVE'
                """, Integer.class, groupId));
    }

    /**
     * 회전 이전과 이후를 포함한 링크 총수를 조회합니다.
     *
     * 재발급으로 과거 이력이 삭제되지 않아야 합니다.
     * 최초 1건에서 두 번 재발급하면 총 3건이어야 합니다.
     */
    private int totalCount(long groupId) {
        return Objects.requireNonNull(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM group_invite_links WHERE group_id = ?",
                Integer.class, groupId));
    }

    /**
     * 현재 활성 링크의 해시를 조회합니다.
     *
     * 신규 응답에서 추출한 원문 토큰의 해시와 비교합니다.
     * 단일 활성 링크 제약이 위반되면 정상 실행되지 않아야 합니다.
     */
    private String activeHash(long groupId) {
        return jdbcTemplate.queryForObject("""
                SELECT token_hash
                  FROM group_invite_links
                 WHERE group_id = ?
                   AND status = 'ACTIVE'
                """, String.class, groupId);
    }

    /**
     * 특정 토큰 해시의 현재 상태를 조회합니다.
     *
     * 과거 토큰과 신규 토큰이 뒤섞이지 않았는지 검증합니다.
     * 조회 대상은 UNIQUE token_hash로 정확히 한 행입니다.
     */
    private String statusForHash(String hash) {
        return jdbcTemplate.queryForObject(
                "SELECT status FROM group_invite_links WHERE token_hash = ?",
                String.class, hash);
    }

    /**
     * 폐기된 초대 링크에 무효화 시각이 기록되었는지 확인합니다.
     *
     * 재발급 전에 발생한 발급 이력은 삭제하지 않습니다.
     * 기존 링크에만 invalidated_at이 채워져야 합니다.
     */
    private java.sql.Timestamp invalidatedAtForHash(String hash) {
        return jdbcTemplate.queryForObject("""
                SELECT invalidated_at
                  FROM group_invite_links
                 WHERE token_hash = ?
                """, java.sql.Timestamp.class, hash);
    }

    private record Fixture(long userId, long groupId) {}
}
