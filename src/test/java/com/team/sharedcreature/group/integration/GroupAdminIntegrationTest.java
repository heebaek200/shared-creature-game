package com.team.sharedcreature.group.integration;

import com.team.sharedcreature._core.exception.BusinessException;
import com.team.sharedcreature.group.api.MyGroupResponse;
import com.team.sharedcreature.group.application.GroupAdminService;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * STEP 3 관리자 변경 기능을 실제 MySQL에서 검증하는 회귀 테스트입니다.
 *
 * PRIMARY 유일성 제약, JPA flush 순서, 감사 이력과 그룹의 관리자 참조를 확인합니다.
 * 기존 테스트와 동일한 test 프로필·MySQL 환경 변수를 사용하며 각 테스트는 롤백됩니다.
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
@EnabledIfEnvironmentVariable(named = "TEST_DB_USERNAME", matches = ".+")
class GroupAdminIntegrationTest {

    @Autowired private GroupAdminService groupAdminService;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private EntityManager entityManager;

    /**
     * 관리자 역할 부여·동일 역할 재요청·역할 회수의 전체 영속화 경로를 확인합니다.
     *
     * 멤버십 ROLE과 감사 이력을 실제 DB에서 각각 조회합니다.
     * 동일 역할을 두 번 요청해도 이력이 추가되지 않는지 검증합니다.
     */
    @Test
    void DEPUTY_부여_멱등_회수가_DB와_감사이력에_정확하게_반영된다() {
        Fixture fixture = createFixture();

        // MEMBER를 DEPUTY로 변경하고 변경 기록을 검사합니다.
        var granted = groupAdminService.changeMemberRole(fixture.actorId(), fixture.targetId(), "DEPUTY");
        entityManager.flush();  // Dirty Checking을 JDBC 직접 조회 전에 반영합니다.
        assertThat(granted.userId()).isEqualTo(fixture.targetId());
        assertThat(granted.role()).isEqualTo("DEPUTY");
        assertThat(membershipRole(fixture.targetId())).isEqualTo("DEPUTY");
        assertThat(changeCount(fixture.groupId())).isEqualTo(1);
        assertAudit(fixture, "GRANT_DEPUTY", "MEMBER", "DEPUTY");

        // 역할이 같으면 감사 이력은 새로 생성되지 않습니다.
        var repeated = groupAdminService.changeMemberRole(fixture.actorId(), fixture.targetId(), "DEPUTY");
        assertThat(repeated.role()).isEqualTo("DEPUTY");
        assertThat(changeCount(fixture.groupId())).isEqualTo(1);

        // 역할 회수 후에는 MEMBER와 회수 이력이 남아야 합니다.
        var revoked = groupAdminService.changeMemberRole(fixture.actorId(), fixture.targetId(), "MEMBER");
        entityManager.flush();  // JDBC에서 실제 변경된 ROLE을 확인하도록 Flush합니다.
        assertThat(revoked.role()).isEqualTo("MEMBER");
        assertThat(membershipRole(fixture.targetId())).isEqualTo("MEMBER");
        assertThat(changeCount(fixture.groupId())).isEqualTo(2);
        assertAudit(fixture, "REVOKE_DEPUTY", "DEPUTY", "MEMBER");
    }

    /**
     * MEMBER로의 주 관리자 이전 시 그룹 참조와 두 멤버십을 검증합니다.
     *
     * 예전에 발생했던 actor/target 뒤바뀜 회귀를 직접 검출하는 테스트입니다.
     * 실제 MySQL의 생성 컬럼·PRIMARY UNIQUE 제약도 함께 통과해야 합니다.
     */
    @Test
    void PRIMARY_이전은_그룹_관리자_ID_유일성_응답_감사이력을_일치시킨다() {
        Fixture fixture = createFixture();

        // 서비스 결과와 실제 DB 저장 결과가 일치해야 합니다.
        MyGroupResponse response = groupAdminService.transferPrimaryAdmin(fixture.actorId(), fixture.targetId());
        assertThat(response.primaryAdmin().userId()).isEqualTo(fixture.targetId());
        assertThat(response.myMembership().role()).isEqualTo("MEMBER");
        assertThat(membershipRole(fixture.actorId())).isEqualTo("MEMBER");
        assertThat(membershipRole(fixture.targetId())).isEqualTo("PRIMARY");
        assertThat(groupPrimaryId(fixture.groupId())).isEqualTo(fixture.targetId());

        // 그룹에 활성 PRIMARY가 정확히 한 명이어야 합니다.
        assertThat(primaryCount(fixture.groupId())).isEqualTo(1);
        assertThat(activeMemberCount(fixture.groupId())).isEqualTo(3);
        assertThat(storedMemberCount(fixture.groupId())).isEqualTo(3);
        assertAudit(fixture, "TRANSFER_PRIMARY", "MEMBER", "PRIMARY");
    }

    /**
     * DEPUTY에게 주 관리자 권한을 이전하는 경로를 확인합니다.
     *
     * 대상의 이전 역할이 DEPUTY였다는 사실이 이력에 남아야 합니다.
     * 그룹 참조와 멤버십에는 새로운 PRIMARY가 한 명만 존재해야 합니다.
     */
    @Test
    void DEPUTY를_PRIMARY로_이전해도_역할_유일성과_감사이력이_맞는다() {
        Fixture fixture = createFixture();
        jdbcTemplate.update("""
                UPDATE group_memberships
                   SET role = 'DEPUTY'
                 WHERE group_id = ?
                   AND user_id = ?
                """, fixture.groupId(), fixture.targetId());

        // 새 관리자는 유일한 PRIMARY여야 합니다.
        var result = groupAdminService.transferPrimaryAdmin(fixture.actorId(), fixture.targetId());
        assertThat(result.primaryAdmin().userId()).isEqualTo(fixture.targetId());
        assertThat(membershipRole(fixture.actorId())).isEqualTo("MEMBER");
        assertThat(membershipRole(fixture.targetId())).isEqualTo("PRIMARY");
        assertThat(primaryCount(fixture.groupId())).isEqualTo(1);
        assertThat(groupPrimaryId(fixture.groupId())).isEqualTo(fixture.targetId());
        assertAudit(fixture, "TRANSFER_PRIMARY", "DEPUTY", "PRIMARY");
    }

    /**
     * PRIMARY를 이전한 뒤 구 관리자는 다시 역할을 변경할 수 없어야 합니다.
     *
     * 서비스가 세션 권한을 신뢰하지 않고 DB 최신 역할을 검사하는지 확인합니다.
     * 이 검증은 Service 레벨이며 SecurityFilterChain의 HTTP 403은 별도로 검증합니다.
     */
    @Test
    void 이전_PRIMARY의_재차_역할변경은_ROLE_FORBIDDEN으로_차단된다() {
        Fixture fixture = createFixture();
        groupAdminService.transferPrimaryAdmin(fixture.actorId(), fixture.targetId());

        // 기존 관리자의 ROLE_PRIMARY 권한은 DB상 이미 사라졌습니다.
        assertThat(membershipRole(fixture.actorId())).isEqualTo("MEMBER");
        assertThatThrownBy(() -> groupAdminService.changeMemberRole(
                fixture.actorId(), fixture.otherId(), "DEPUTY"))
                .isInstanceOfSatisfying(BusinessException.class, exception ->
                        assertThat(exception.code()).isEqualTo("ROLE_FORBIDDEN"));
    }

    /**
     * 같은 그룹에 소속되지 않은 사용자는 관리자 이전 대상이 아닙니다.
     *
     * 존재하는 정상 활성 계정이 다른 그룹 소속이라면 404로 거부합니다.
     * 감사 이력이나 기존 PRIMARY 변경이 발생해서는 안 됩니다.
     */
    @Test
    void 그룹_외부_회원에게_PRIMARY_이전은_RESOURCE_NOT_FOUND이다() {
        Fixture fixture = createFixture();
        Fixture otherGroup = createFixture();
        long outsiderId = otherGroup.targetId();

        // 외부 사용자는 다른 활성 그룹에 소속되어 있습니다.
        assertThatThrownBy(() -> groupAdminService.transferPrimaryAdmin(fixture.actorId(), outsiderId))
                .isInstanceOfSatisfying(BusinessException.class, exception ->
                        assertThat(exception.code()).isEqualTo("RESOURCE_NOT_FOUND"));
    }

    /**
     * 현재 상태가 ACTIVE가 아닌 계정으로 관리자 권한을 이전하지 않습니다.
     *
     * 사용자 상태를 직접 바꾼 후 API가 최종 상태를 다시 조회하는지 검증합니다.
     * 대상 멤버십은 그대로 존재하여 사용자 상태 검사만 실패하는 Fixture입니다.
     */
    @Test
    void 비활성_회원에게_PRIMARY_이전은_RESOURCE_NOT_FOUND이다() {
        Fixture fixture = createFixture();
        jdbcTemplate.update("UPDATE users SET status = 'SUSPENDED' WHERE id = ?", fixture.targetId());

        // 그룹 구성원이더라도 활성 계정이 아니면 관리자 이전을 거부합니다.
        assertThatThrownBy(() -> groupAdminService.transferPrimaryAdmin(fixture.actorId(), fixture.targetId()))
                .isInstanceOfSatisfying(BusinessException.class, exception ->
                        assertThat(exception.code()).isEqualTo("RESOURCE_NOT_FOUND"));
    }

    /**
     * 테스트별 독립적인 활성 그룹과 회원 세 명을 만듭니다.
     *
     * SQL은 실제 테이블 제약과 생성 컬럼을 그대로 사용합니다.
     * 그룹을 생성한 다음 멤버십을 추가하며, 각 테스트가 끝나면 트랜잭션이 롤백됩니다.
     *
     * @return 관리 작업에 사용할 그룹 및 사용자 PK
     */
    private Fixture createFixture() {
        long actorId = insertUser("관리자");
        long targetId = insertUser("대상");
        long otherId = insertUser("일반");
        String groupName = "STEP3검증" + UUID.randomUUID().toString().substring(0, 8);

        // 그룹이 사용자와 FK로 연결되므로 사용자부터 생성합니다.
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
                ) VALUES (?, ?, '', 'PUBLIC', 'ACTIVE', 20, 3, ?, 0, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
                """, groupName, groupName.toLowerCase(Locale.ROOT), actorId);

        // PRIMARY 1명과 일반 회원 2명으로 시작합니다.
        insertMembership(groupId, actorId, "PRIMARY");
        insertMembership(groupId, targetId, "MEMBER");
        insertMembership(groupId, otherId, "MEMBER");
        return new Fixture(groupId, actorId, targetId, otherId);
    }

    /**
     * 필수 컬럼만 채워 ACTIVE 계정과 프로필을 생성합니다.
     *
     * 닉네임은 자동 증가 키를 붙여 UNIQUE 충돌을 막습니다.
     * 실제 서비스 조회는 User와 UserProfile 엔티티를 통해 진행됩니다.
     *
     * @param prefix 회원을 구별하기 위한 짧은 이름
     * @return 생성된 사용자 ID
     */
    private long insertUser(String prefix) {
        long userId = insertAndReturnId("""
                INSERT INTO users (
                    status
                  , global_role
                  , last_login_at
                  , last_active_at
                  , version
                  , created_at
                  , updated_at
                ) VALUES ('ACTIVE', 'USER', UTC_TIMESTAMP(6), UTC_TIMESTAMP(6), 0, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
                """);
        String nickname = prefix + userId;

        // Service의 응답 변환은 사용자 프로필도 필요로 합니다.
        jdbcTemplate.update("""
                INSERT INTO user_profiles (
                    user_id
                  , nickname
                  , normalized_nickname
                  , version
                  , created_at
                  , updated_at
                ) VALUES (?, ?, ?, 0, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
                """, userId, nickname, nickname.toLowerCase(Locale.ROOT));
        return userId;
    }

    /**
     * 지정된 사용자에게 ACTIVE 멤버십을 등록합니다.
     *
     * generated column primary_group_id는 MySQL이 직접 계산하도록 둡니다.
     * 테스트 완료 후 롤백되므로 테이블에 임시 레코드가 남지 않습니다.
     *
     * @param groupId 그룹 PK
     * @param userId 사용자 PK
     * @param role 생성할 멤버십 역할
     */
    private void insertMembership(long groupId, long userId, String role) {
        // 실제 DB에 유일성 제약이 적용된 상태로 삽입합니다.
        jdbcTemplate.update("""
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
                """, groupId, userId, role);
    }

    /**
     * INSERT 후 JDBC가 반환한 자동 증가 PK를 반환합니다.
     *
     * SQL 값은 PreparedStatement로 바인딩하여 SQL 문자열을 직접 결합하지 않습니다.
     * 같은 테스트 트랜잭션에서 생성한 키는 FK에 재사용합니다.
     *
     * @param sql INSERT 문
     * @param values 바인딩할 값 목록
     * @return 생성된 PK
     */
    private long insertAndReturnId(String sql, Object... values) {
        GeneratedKeyHolder holder = new GeneratedKeyHolder();

        // PreparedStatement에 SQL 매개변수를 순서대로 설정합니다.
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
     * 대상 활성 회원의 최신 역할을 DB에서 직접 읽습니다.
     *
     * JPA의 영속성 캐시에 의존하지 않고 실제 SQL 결과를 확인합니다.
     * 대상 사용자는 테스트 내에서 한 그룹에만 활성 소속됩니다.
     *
     * @param userId 조회할 회원 ID
     * @return 역할 문자열
     */
    private String membershipRole(long userId) {
        // 활성 멤버십 역할을 직접 검증합니다.
        return jdbcTemplate.queryForObject("""
                SELECT role
                  FROM group_memberships
                 WHERE user_id = ?
                   AND status = 'ACTIVE'
                """, String.class, userId);
    }

    /**
     * 그룹의 PRIMARY 참조를 SQL로 직접 조회합니다.
     *
     * 이전에 발견한 actor/target 반전 버그를 검출하는 핵심 검증 지점입니다.
     * 응답 DTO만 확인하는 대신 실제 FK 값을 비교합니다.
     *
     * @param groupId 그룹 PK
     * @return 저장된 주 관리자 사용자 ID
     */
    private Long groupPrimaryId(long groupId) {
        // 그룹 테이블에 반영된 FK를 확인합니다.
        return jdbcTemplate.queryForObject("""
                SELECT primary_admin_user_id
                  FROM game_groups
                 WHERE id = ?
                """, Long.class, groupId);
    }

    /**
     * 한 그룹에 활성 PRIMARY 멤버십이 한 건뿐인지 확인합니다.
     *
     * MySQL의 UNIQUE 생성 컬럼 제약과 서비스 상태 변경 결과를 함께 검증합니다.
     * 구 PRIMARY가 남거나 둘 다 MEMBER로 남는 회귀를 찾아냅니다.
     *
     * @param groupId 그룹 PK
     * @return PRIMARY 수
     */
    private int primaryCount(long groupId) {
        // UNIQUE 제약과 실제 ACTIVE 멤버십 수를 비교합니다.
        return Objects.requireNonNull(jdbcTemplate.queryForObject("""
                SELECT COUNT(*)
                  FROM group_memberships
                 WHERE group_id = ?
                   AND role = 'PRIMARY'
                   AND status = 'ACTIVE'
                """, Integer.class, groupId));
    }

    /**
     * 실제 활성 멤버십의 개수를 조회합니다.
     *
     * 역할 이전이 단순한 권한 변경이며 회원 수에는 영향을 주지 않음을 검증합니다.
     * 그룹 행의 current_member_count와 비교할 기준값으로 사용합니다.
     *
     * @param groupId 그룹 PK
     * @return 활성 구성원 수
     */
    private int activeMemberCount(long groupId) {
        // 탈퇴·강퇴 회원을 제외한 활성 인원만 계산합니다.
        return Objects.requireNonNull(jdbcTemplate.queryForObject("""
                SELECT COUNT(*)
                  FROM group_memberships
                 WHERE group_id = ?
                   AND status = 'ACTIVE'
                """, Integer.class, groupId));
    }

    /**
     * 그룹 행의 캐시된 인원 수를 조회합니다.
     *
     * PRIMARY 이전으로 구성원 수가 변하지 않는다는 불변식을 확인합니다.
     * 활성 멤버십 수와 일치해야 합니다.
     *
     * @param groupId 그룹 PK
     * @return 그룹의 저장된 회원 수
     */
    private int storedMemberCount(long groupId) {
        // 그룹 행의 인원 카운터를 직접 읽습니다.
        return Objects.requireNonNull(jdbcTemplate.queryForObject("""
                SELECT current_member_count
                  FROM game_groups
                 WHERE id = ?
                """, Integer.class, groupId));
    }

    /**
     * 특정 관리자 작업에 해당하는 감사 이력의 존재와 필드를 검증합니다.
     *
     * 실행자·대상·이전 역할·새 역할까지 비교하여 저장 순서 오류를 찾습니다.
     * PRIMARY 이전과 부 관리자 부여·회수 테스트에서 공동 사용합니다.
     *
     * @param fixture 테스트 사용자·그룹 정보
     * @param changeType 검증할 작업 종류
     * @param previousRole 변경 전 역할
     * @param nextRole 변경 후 역할
     */
    private void assertAudit(Fixture fixture, String changeType, String previousRole, String nextRole) {
        // 실행자와 대상이 서로 바뀌지 않아야 합니다.
        int count = Objects.requireNonNull(jdbcTemplate.queryForObject("""
                SELECT COUNT(*)
                  FROM group_admin_changes
                 WHERE group_id = ?
                   AND actor_user_id = ?
                   AND target_user_id = ?
                   AND change_type = ?
                   AND previous_role = ?
                   AND new_role = ?
                """, Integer.class,
                fixture.groupId(), fixture.actorId(), fixture.targetId(),
                changeType, previousRole, nextRole));
        assertThat(count).isEqualTo(1);
    }

    /**
     * 그룹 관리 감사 로그의 전체 기록 수를 조회합니다.
     *
     * 멱등 요청이 불필요한 변경 이력을 쌓지 않는지 확인합니다.
     * 테스트 Fixture는 그룹 생성 이력을 직접 삽입하지 않으므로 초기값은 0입니다.
     *
     * @param groupId 그룹 PK
     * @return 감사 이력 수
     */
    private int changeCount(long groupId) {
        // 테스트 중 발생한 관리 명령만 집계합니다.
        return Objects.requireNonNull(jdbcTemplate.queryForObject("""
                SELECT COUNT(*)
                  FROM group_admin_changes
                 WHERE group_id = ?
                """, Integer.class, groupId));
    }

    /** 각 테스트가 직접 생성하는 그룹과 세 사용자의 PK를 보관합니다. */
    private record Fixture(long groupId, long actorId, long targetId, long otherId) {}
}
