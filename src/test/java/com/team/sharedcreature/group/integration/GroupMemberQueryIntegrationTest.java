package com.team.sharedcreature.group.integration;

import com.team.sharedcreature.group.api.GroupMemberResponse;
import com.team.sharedcreature.group.infrastructure.GroupMembershipRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 실제 MySQL에서 그룹 구성원 DTO JOIN 및 페이징을 검증합니다.
 *
 * 테스트 프로필의 전용 MySQL 스키마를 사용합니다.
 * 테스트 중 생성한 모든 행은 테스트 트랜잭션 롤백으로 정리합니다.
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
@EnabledIfEnvironmentVariable(named = "TEST_DB_USERNAME", matches = ".+")
class GroupMemberQueryIntegrationTest {

    private static final Instant START = Instant.parse("2026-11-01T00:00:00Z");
    private static final Instant ACTIVE_AT = Instant.parse("2026-11-04T03:00:00Z");

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private GroupMembershipRepository membershipRepository;

    /**
     * 활성 구성원만 조회하고 DTO 필드·정렬·페이지 메타데이터를 검증합니다.
     *
     * 한 그룹에 활성 3명과 탈퇴 1명을 넣고 다른 그룹에도 회원을 생성합니다.
     * 두 페이지에서 자신의 그룹 ACTIVE 회원만 정확한 순서로 조회해야 합니다.
     */
    @Test
    void 활성_구성원만_조회하며_JOIN_COUNT_정렬과_페이징이_정확하다() {
        // 한 그룹의 관리자·일반 회원 둘과 종료된 회원을 준비합니다.
        long primary = insertUser();
        long memberA = insertUser();
        long memberB = insertUser();
        long leftMember = insertUser();
        long outsider = insertUser();

        String primaryNick = "주관리자" + primary;
        String memberANick = "일반가" + memberA;
        String memberBNick = "일반나" + memberB;
        insertProfile(primary, primaryNick);
        insertProfile(memberA, memberANick);
        insertProfile(memberB, memberBNick);
        insertProfile(leftMember, "탈퇴자" + leftMember);
        insertProfile(outsider, "외부회원" + outsider);

        // 서로 다른 그룹의 회원이 섞이지 않도록 별도 그룹도 생성합니다.
        String unique = "조회" + UUID.randomUUID().toString().substring(0, 8);
        long groupId = insertGroup(unique + "가", primary, 3);
        long anotherGroupId = insertGroup(unique + "나", outsider, 1);

        insertMembership(groupId, primary, "PRIMARY", "ACTIVE", START, null);
        insertMembership(groupId, memberA, "MEMBER", "ACTIVE", START.plusSeconds(60), null);
        insertMembership(groupId, memberB, "MEMBER", "ACTIVE", START.plusSeconds(120), null);
        insertMembership(groupId, leftMember, "MEMBER", "LEFT", START.plusSeconds(180), START.plusSeconds(300));
        insertMembership(anotherGroupId, outsider, "PRIMARY", "ACTIVE", START, null);

        // Repository의 실제 JPQL, DTO 생성과 별도 COUNT 쿼리를 실행합니다.
        Page<GroupMemberResponse> first = membershipRepository
                .findActiveMemberResponsesByGroupId(groupId, PageRequest.of(0, 2));
        Page<GroupMemberResponse> second = membershipRepository
                .findActiveMemberResponsesByGroupId(groupId, PageRequest.of(1, 2));

        // 퇴장 회원과 다른 그룹의 회원을 제외한 전체 개수를 검증합니다.
        assertThat(first.getTotalElements()).isEqualTo(3);
        assertThat(first.getTotalPages()).isEqualTo(2);
        assertThat(first.getSize()).isEqualTo(2);
        assertThat(first.getNumber()).isZero();
        assertThat(second.getTotalElements()).isEqualTo(3);
        assertThat(second.getNumber()).isEqualTo(1);

        // 가입 시각 오름차순으로 페이지가 나뉘는지 검증합니다.
        assertThat(first.getContent())
                .extracting(GroupMemberResponse::nickname)
                .containsExactly(primaryNick, memberANick);
        assertThat(second.getContent())
                .extracting(GroupMemberResponse::nickname)
                .containsExactly(memberBNick);

        // 반환 DTO에 활동 시각·역할·상태·멤버십 ID가 포함되는지 확인합니다.
        GroupMemberResponse firstMember = first.getContent().get(0);
        assertThat(firstMember.userId()).isEqualTo(primary);
        assertThat(firstMember.membershipId()).isPositive();
        assertThat(firstMember.role()).isEqualTo("PRIMARY");
        assertThat(firstMember.status()).isEqualTo("ACTIVE");
        assertThat(firstMember.joinedAt()).isEqualTo(START);
        assertThat(firstMember.lastActiveAt()).isEqualTo(ACTIVE_AT);
    }

    /**
     * 필수 컬럼을 채운 활성 사용자를 데이터베이스에 생성합니다.
     *
     * 마지막 인증 활동 시각은 고정하여 DTO 변환 결과를 검증합니다.
     * 생성된 PK는 테스트 데이터 간 FK 연결에 사용합니다.
     */
    private long insertUser() {
        return insertAndReturnId("""
                INSERT INTO users (
                    status
                  , global_role
                  , last_login_at
                  , last_active_at
                  , version
                  , created_at
                  , updated_at
                ) VALUES ('ACTIVE', 'USER', UTC_TIMESTAMP(6), ?, 0, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
                """, Timestamp.from(ACTIVE_AT));
    }

    /**
     * 그룹 목록 조회에서 사용할 사용자 프로필을 생성합니다.
     *
     * 전역 닉네임 UNIQUE 제약을 고려해 회원 PK를 포함합니다.
     * 사용자 프로필은 활성 회원뿐 아니라 종료된 회원에게도 생성합니다.
     *
     * @param userId 프로필 소유 회원 ID
     * @param nickname 조회 시 반환할 표시 이름
     */
    private void insertProfile(long userId, String nickname) {
        // 정규화 값은 이번 테스트에서 소문자 처리만으로 충분합니다.
        jdbcTemplate.update("""
                INSERT INTO user_profiles (
                    user_id
                  , nickname
                  , normalized_nickname
                  , version
                  , created_at
                  , updated_at
                ) VALUES (?, ?, ?, 0, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
                """, userId, nickname, nickname.toLowerCase(java.util.Locale.ROOT));
    }

    /**
     * 현재 관리자와 구성원 수를 가진 활성 그룹을 생성합니다.
     *
     * 외래 키가 연결된 주 관리자는 그룹보다 먼저 생성되어야 합니다.
     * 그룹명은 테스트마다 고유하게 생성해 UNIQUE 제약 충돌을 피합니다.
     *
     * @param name 그룹 이름
     * @param primaryUserId 주 관리자 ID
     * @param memberCount 활성 회원 수
     * @return 생성된 그룹 PK
     */
    private long insertGroup(String name, long primaryUserId, int memberCount) {
        return insertAndReturnId("""
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
                ) VALUES (?, ?, '', 'PUBLIC', 'ACTIVE', 20, ?, ?, 0, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
                """, name, name.toLowerCase(java.util.Locale.ROOT), memberCount, primaryUserId);
    }

    /**
     * 활성 또는 자진 탈퇴 멤버십을 생성합니다.
     *
     * 종료 회원은 endedAt과 SELF 사유를 함께 보관합니다.
     * 생성 컬럼은 MySQL의 스키마 로직에 맡겨 기존 UNIQUE 규칙을 검증합니다.
     *
     * @param groupId 소속 그룹 PK
     * @param userId 사용자 PK
     * @param role 그룹 내 역할
     * @param status 멤버십 상태
     * @param joinedAt 가입 시각
     * @param endedAt 탈퇴 시각. 활성 회원은 null
     */
    private void insertMembership(
            long groupId,
            long userId,
            String role,
            String status,
            Instant joinedAt,
            Instant endedAt
    ) {
        // 가입·종료 시각이 달라도 고정 정렬이 유지되도록 준비합니다.
        jdbcTemplate.update("""
                INSERT INTO group_memberships (
                    group_id
                  , user_id
                  , role
                  , status
                  , joined_at
                  , ended_at
                  , exit_reason
                  , version
                  , created_at
                  , updated_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, 0, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
                """, groupId, userId, role, status, Timestamp.from(joinedAt),
                endedAt == null ? null : Timestamp.from(endedAt),
                endedAt == null ? null : "SELF");
    }

    /**
     * INSERT 후 JDBC 생성 키를 추출하는 공통 헬퍼입니다.
     *
     * 파라미터는 PreparedStatement에 순서대로 바인딩합니다.
     * 테스트 종료 시 @Transactional 롤백되므로 명시적 삭제는 하지 않습니다.
     *
     * @param sql INSERT 문
     * @param params 바인딩할 값
     * @return 생성된 BIGINT PK
     */
    private long insertAndReturnId(String sql, Object... params) {
        GeneratedKeyHolder keyHolder = new GeneratedKeyHolder();

        // DB에서 자동 증가 PK를 반환하도록 INSERT를 수행합니다.
        jdbcTemplate.update(connection -> {
            PreparedStatement statement = connection.prepareStatement(
                    sql, Statement.RETURN_GENERATED_KEYS
            );
            for (int index = 0; index < params.length; index++) {
                statement.setObject(index + 1, params[index]);
            }
            return statement;
        }, keyHolder);

        return Objects.requireNonNull(keyHolder.getKey()).longValue();
    }
}
