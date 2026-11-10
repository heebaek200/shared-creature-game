package com.team.sharedcreature.group.integration;

import com.team.sharedcreature._core.exception.BusinessException;
import com.team.sharedcreature._core.security.Hashing;
import com.team.sharedcreature._core.security.LoginUser;
import com.team.sharedcreature.group.application.GroupAdminService;
import com.team.sharedcreature.group.application.GroupInvitationManagementService;
import com.team.sharedcreature.group.application.GroupMembershipService;
import com.team.sharedcreature.group.application.GroupRemovalService;
import com.team.sharedcreature.auth.infrastructure.UserRepository;
import com.team.sharedcreature.group.infrastructure.GroupRepository;
import com.team.sharedcreature.group.infrastructure.GroupMembershipRepository;
import com.team.sharedcreature.group.infrastructure.GroupInviteLinkRepository;
import jakarta.persistence.EntityManager;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.dao.DataIntegrityViolationException;
import java.time.Clock;
import static org.mockito.Mockito.*;
import static org.mockito.AdditionalAnswers.delegatesTo;
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
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Issue #15 STEP 7: 실제 MySQL에서 그룹 관리 명령 간 동시성 및 롤백을 검증합니다.
 *
 * 각 업무 호출은 Spring AOP 프록시를 경유하여 독립 트랜잭션으로 실행합니다.
 * 테스트 클래스에는 @Transactional을 붙이지 않습니다. Fixture는 JDBC로 커밋합니다.
 * 사용자·그룹의 고유 ID를 생성하고 FK 역순으로 정리하여 기존 데이터와 격리합니다.
 */
@SpringBootTest
@ActiveProfiles("test")
@EnabledIfEnvironmentVariable(named = "TEST_DB_USERNAME", matches = ".+")
class GroupManagementConsistencyIntegrationTest {

    private static final String PREFIX = "S7";

    @Autowired private GroupAdminService adminService;
    @Autowired private GroupRemovalService removalService;
    @Autowired private GroupMembershipService membershipService;
    @Autowired private GroupInvitationManagementService invitationService;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private UserRepository userRepository;
    @Autowired private GroupRepository groupRepository;
    @Autowired private GroupMembershipRepository membershipRepository;
    @Autowired private GroupInviteLinkRepository inviteLinkRepository;
    @Autowired private EntityManager entityManager;
    @Autowired private Clock clock;
    @Autowired private PlatformTransactionManager transactionManager;

    private ExecutorService executor;
    private final List<Long> createdUserIds = new ArrayList<>();
    private final List<Long> createdGroupIds = new ArrayList<>();

    /**
     * 동시 요청에 필요한 작업 스레드를 준비합니다.
     * 모든 테스트는 기존 DB의 다른 그룹과 겹치지 않는 새 PK를 사용합니다.
     * 그룹 초기화 DML은 @Transactional 테스트 안에서 수행하지 않습니다.
     */
    @BeforeEach
    void setUp() {
        // 두 업무 요청은 별도 스레드의 독립 트랜잭션에서 실행됩니다.
        executor = Executors.newFixedThreadPool(2);
    }

    /**
     * 테스트가 생성한 감사·가입 제한·초대·멤버십·그룹·사용자를 순서대로 삭제합니다.
     * 성공과 실패 양쪽 모두 AfterEach에서 정리합니다.
     * 다른 그룹의 기존 회원 및 초대 이력에는 접근하지 않습니다.
     */
    @AfterEach
    void tearDown() {
        try {
            for (Long groupId : createdGroupIds) {
                // FK를 참조하는 업무 이력을 먼저 정리합니다.
                jdbc.update("DELETE FROM moderation_actions WHERE group_id = ?", groupId);
                jdbc.update("DELETE FROM group_admin_changes WHERE group_id = ?", groupId);
                jdbc.update("DELETE FROM group_invite_links WHERE group_id = ?", groupId);
            }
            for (Long userId : createdUserIds) {
                jdbc.update("DELETE FROM group_join_restrictions WHERE user_id = ?", userId);
            }
            for (Long groupId : createdGroupIds) {
                jdbc.update("DELETE FROM group_memberships WHERE group_id = ?", groupId);
                jdbc.update("DELETE FROM game_groups WHERE id = ?", groupId);
            }
            for (Long userId : createdUserIds) {
                jdbc.update("DELETE FROM user_profiles WHERE user_id = ?", userId);
                jdbc.update("DELETE FROM users WHERE id = ?", userId);
            }
        } finally {
            // 실패한 테스트가 스레드를 남기지 않게 합니다.
            executor.shutdownNow();
        }
    }

    /**
     * 주 관리자 이전과 대상 강퇴가 경쟁할 때 둘 다 성공할 수 없음을 검증합니다.
     * 이전이 먼저면 강퇴는 권한 부족이고, 강퇴가 먼저면 이전 대상이 없습니다.
     * 최종 PRIMARY 유일성·정확한 그룹 인원·감사 로그를 SQL로 확인합니다.
     */
    @Test
    void PRIMARY_이전과_대상_강퇴가_동시_발생해도_정합성이_유지된다() throws Exception {
        Fixture f = fixture(false);

        // 대상 B는 73시간 미활동, A는 PRIMARY입니다.
        List<String> results = race(
                () -> adminService.transferPrimaryAdmin(f.actor(), f.target()),
                () -> removalService.removeInactiveMember(f.actor(), f.target(), "STEP7 강퇴")
        );
        assertThat(results).contains("SUCCESS");
        assertThat(results.stream().filter("SUCCESS"::equals).count()).isEqualTo(1);
        assertThat(results.stream().noneMatch(s -> s.startsWith("UNEXPECTED"))).isTrue();

        // 어느 명령이 먼저 Commit됐든 PRIMARY는 한 명입니다.
        assertInvariants(f.group());
        if ("PRIMARY".equals(activeRole(f.target()))) {
            assertThat(results).contains("ROLE_FORBIDDEN");
            assertThat(count("SELECT COUNT(*) FROM moderation_actions WHERE group_id = ?", f.group())).isZero();
            assertThat(count("SELECT current_member_count FROM game_groups WHERE id = ?", f.group())).isEqualTo(3);
        } else {
            assertThat(membershipStatus(f.target())).isEqualTo("REMOVED");
            assertThat(count("SELECT current_member_count FROM game_groups WHERE id = ?", f.group())).isEqualTo(2);
            assertThat(count("SELECT COUNT(*) FROM moderation_actions WHERE group_id = ?", f.group())).isEqualTo(1);
        }
    }

    /**
     * 대상의 자진 탈퇴와 관리자의 비활동 강퇴가 경쟁할 때 중복 차감하지 않습니다.
     * 종료 상태는 LEFT 또는 REMOVED 중 하나여야 하며 24시간 제한은 단 한 건입니다.
     * 실제 강퇴가 성공한 경우에만 ModerationAction이 기록되어야 합니다.
     */
    @Test
    void 자진_탈퇴와_강퇴의_경쟁에서_회원_수는_한명만_감소한다() throws Exception {
        Fixture f = fixture(false);

        // 자진 탈퇴와 강퇴는 같은 멤버십을 서로 다른 사용자로 잠급니다.
        List<String> results = race(
                () -> membershipService.leaveMyGroup(f.target()),
                () -> removalService.removeInactiveMember(f.actor(), f.target(), "STEP7 강퇴")
        );
        assertThat(results.stream().filter("SUCCESS"::equals).count()).isEqualTo(1);
        assertThat(results.stream().noneMatch(s -> s.startsWith("UNEXPECTED"))).isTrue();
        assertThat(membershipStatus(f.target())).isIn("LEFT", "REMOVED");
        assertThat(count("SELECT COUNT(*) FROM group_join_restrictions WHERE user_id = ?", f.target())).isEqualTo(1);
        assertThat(count("SELECT current_member_count FROM game_groups WHERE id = ?", f.group())).isEqualTo(2);
        assertThat(count("SELECT COUNT(*) FROM moderation_actions WHERE group_id = ?", f.group()))
                .isEqualTo("REMOVED".equals(membershipStatus(f.target())) ? 1 : 0);
        assertInvariants(f.group());
    }

    /**
     * 동일 회원이 여러 브라우저에서 탈퇴를 중복 요청한 상황을 검증합니다.
     * 탈퇴는 정확히 한 번만 성공하고 인원은 한 명만 감소해야 합니다.
     * 제한 기록은 사용자당 한 건만 남아야 합니다.
     */
    @Test
    void 동일_사용자의_중복_탈퇴는_한_번만_반영된다() throws Exception {
        Fixture f = fixture(false);

        // 같은 멤버십을 두 요청에서 동시에 종료하도록 시도합니다.
        List<String> results = race(
                () -> membershipService.leaveMyGroup(f.target()),
                () -> membershipService.leaveMyGroup(f.target())
        );
        assertThat(results.stream().filter("SUCCESS"::equals).count()).isEqualTo(1);
        assertThat(results.stream().noneMatch(s -> s.startsWith("UNEXPECTED"))).isTrue();
        assertThat(membershipStatus(f.target())).isEqualTo("LEFT");
        assertThat(count("SELECT current_member_count FROM game_groups WHERE id = ?", f.group())).isEqualTo(2);
        assertThat(count("SELECT COUNT(*) FROM group_join_restrictions WHERE user_id = ?", f.target())).isEqualTo(1);
        assertInvariants(f.group());
    }

    /**
     * PRIMARY의 역할 부여와 동일 대상 강퇴를 동시에 실행합니다.
     * 역할 부여가 먼저면 강퇴 후 이력이 모두 남을 수 있으나 반대 순서는 불가합니다.
     * 어느 경우에도 종료된 멤버십이 활성으로 부활하거나 인원이 이중 감소하지 않습니다.
     */
    @Test
    void 역할_변경과_강퇴가_경쟁해도_종료된_회원은_부활하지_않는다() throws Exception {
        Fixture f = fixture(false);

        // PRIMARY가 MEMBER를 DEPUTY로 바꾸는 동안 비활동 강퇴합니다.
        List<String> results = race(
                () -> adminService.changeMemberRole(f.actor(), f.target(), "DEPUTY"),
                () -> removalService.removeInactiveMember(f.actor(), f.target(), "STEP7 강퇴")
        );
        assertThat(results).contains("SUCCESS");
        assertThat(results.stream().noneMatch(s -> s.startsWith("UNEXPECTED"))).isTrue();
        assertThat(membershipStatus(f.target())).isEqualTo("REMOVED");
        assertThat(count("SELECT current_member_count FROM game_groups WHERE id = ?", f.group())).isEqualTo(2);
        assertThat(count("SELECT COUNT(*) FROM moderation_actions WHERE group_id = ?", f.group())).isEqualTo(1);
        assertThat(count("SELECT COUNT(*) FROM group_join_restrictions WHERE user_id = ?", f.target())).isEqualTo(1);
        assertInvariants(f.group());
    }

    /**
     * 기존 초대 링크 가입과 PRIMARY의 링크 재발급을 경쟁시킵니다.
     * 가입이 먼저 Commit되면 허용, 링크 회전이 먼저면 기존 링크 가입이 거부됩니다.
     * 최종 활성 초대 링크는 한 건이고 실제 회원 수와 저장 카운터는 일치합니다.
     */
    @Test
    void 초대_재발급과_기존_링크_가입의_경쟁에서_폐기_후_가입은_막힌다() throws Exception {
        Fixture f = fixture(false);
        long oldInviteId = insertInvitation(f.group(), f.actor());

        // 초대 ID로 가입하는 실제 서비스 흐름과 링크 회전을 동시에 실행합니다.
        List<String> results = race(
                () -> invitationService.rotateInvitation(f.actor()),
                () -> membershipService.joinInvitation(
                        new LoginUser(f.invitee(), "초대가입", Set.of()), oldInviteId)
        );
        assertThat(results.get(0)).isEqualTo("SUCCESS");
        assertThat(results.get(1)).isIn("SUCCESS", "INVITE_INVALID");
        assertThat(count("SELECT COUNT(*) FROM group_invite_links WHERE group_id = ? AND status = 'ACTIVE'", f.group()))
                .isEqualTo(1);
        assertThat(count("SELECT COUNT(*) FROM group_invite_links WHERE id = ? AND status = 'INVALIDATED'", oldInviteId))
                .isEqualTo(1);
        assertThat(count("SELECT COUNT(*) FROM group_memberships WHERE user_id = ? AND status = 'ACTIVE'", f.invitee()))
                .isEqualTo("SUCCESS".equals(results.get(1)) ? 1 : 0);
        assertInvariants(f.group());
    }

    /**
     * 동일 PRIMARY가 재발급을 두 번 요청하더라도 활성 링크는 하나여야 합니다.
     * 각 요청은 그룹 행을 비관적으로 잠그며, 이전 토큰은 모두 폐기됩니다.
     * UNIQUE 제약과 이전 링크 UPDATE/신규 INSERT 순서를 실제 MySQL에서 검증합니다.
     */
    @Test
    void 동시_초대_재발급_후에도_활성_링크는_하나뿐이다() throws Exception {
        Fixture f = fixture(false);
        long oldInviteId = insertInvitation(f.group(), f.actor());

        // 두 발급 명령을 거의 동시에 시작합니다.
        List<String> results = race(
                () -> invitationService.rotateInvitation(f.actor()),
                () -> invitationService.rotateInvitation(f.actor())
        );
        assertThat(results).containsExactly("SUCCESS", "SUCCESS");
        assertThat(count("SELECT COUNT(*) FROM group_invite_links WHERE group_id = ?", f.group())).isEqualTo(3);
        assertThat(count("SELECT COUNT(*) FROM group_invite_links WHERE group_id = ? AND status = 'ACTIVE'", f.group()))
                .isEqualTo(1);
        assertThat(count("SELECT COUNT(*) FROM group_invite_links WHERE id = ? AND status = 'INVALIDATED'", oldInviteId))
                .isEqualTo(1);
        assertInvariants(f.group());
    }

    /**
     * 신규 초대 링크 INSERT가 실패하면 이전 링크 무효화도 롤백되어야 합니다.
     * 진짜 Repository는 조회에 사용하고 신규 save만 테스트용 실패로 바꿉니다.
     * TransactionTemplate으로 명시적 트랜잭션을 열어 DB 롤백까지 확인합니다.
     */
    @Test
    void 초대_재발급_저장_실패시_기존_링크는_ACTIVE로_롤백된다() {
        Fixture f = fixture(false);
        long oldInviteId = insertInvitation(f.group(), f.actor());

        // INSERT에만 DB 무결성 오류를 주입하고 그 외 Repository 조회는 실제 DB를 사용합니다.
        GroupInviteLinkRepository failingRepository = mock(
                GroupInviteLinkRepository.class, delegatesTo(inviteLinkRepository));
        doThrow(new DataIntegrityViolationException("STEP7 의도적 신규 링크 저장 실패"))
                .when(failingRepository).save(any(com.team.sharedcreature.group.domain.GroupInviteLink.class));

        // 직접 생성한 서비스는 AOP 프록시가 없으므로 TransactionTemplate으로 감쌉니다.
        GroupInvitationManagementService failingService = new GroupInvitationManagementService(
                userRepository, groupRepository, membershipRepository, failingRepository,
                entityManager, clock, "http://localhost:5173"
        );
        TransactionTemplate tx = new TransactionTemplate(transactionManager);

        assertThatThrownBy(() -> tx.execute(status -> failingService.rotateInvitation(f.actor())))
                .isInstanceOf(DataIntegrityViolationException.class);

        // 서비스 내부 flush가 발생했더라도 롤백 뒤에는 원래 링크가 활성 상태여야 합니다.
        assertThat(count("SELECT COUNT(*) FROM group_invite_links WHERE id = ? AND status = 'ACTIVE'", oldInviteId))
                .isEqualTo(1);
        assertThat(count("SELECT COUNT(*) FROM group_invite_links WHERE group_id = ?", f.group()))
                .isEqualTo(1);
        assertInvariants(f.group());
    }

    /**
     * 강퇴 감사 이력 INSERT가 실패했을 때 전체 트랜잭션이 롤백되는지 검증합니다.
     * Service를 직접 호출하여 Controller 유효성 검사를 의도적으로 우회합니다.
     * Nullable=false 감사 사유에 null을 전달해 예외가 발생하도록 합니다.
     */
    @Test
    void 감사_이력_저장_실패는_강퇴와_인원_제한_변경을_전부_롤백한다() {
        Fixture f = fixture(false);

        // null 감사 사유는 NOT NULL 제약을 위반하므로 커밋이 실패해야 합니다.
        assertThatThrownBy(() -> removalService.removeInactiveMember(f.actor(), f.target(), null))
                .isInstanceOf(RuntimeException.class);

        // Service가 예외 발생 전 Dirty Checking한 데이터도 원복되어야 합니다.
        assertThat(membershipStatus(f.target())).isEqualTo("ACTIVE");
        assertThat(count("SELECT current_member_count FROM game_groups WHERE id = ?", f.group())).isEqualTo(3);
        assertThat(count("SELECT COUNT(*) FROM moderation_actions WHERE group_id = ?", f.group())).isZero();
        assertThat(count("SELECT COUNT(*) FROM group_join_restrictions WHERE user_id = ?", f.target())).isZero();
        assertInvariants(f.group());
    }

    /**
     * 마지막 PRIMARY 자진 탈퇴 시 그룹 종료와 초대 무효화가 함께 확정됩니다.
     * 시즌을 생성하지 않은 Fixture이므로 연관 시즌 종료 분기는 실행되지 않습니다.
     * 활성 멤버십과 PRIMARY FK가 제거되고 가입 제한이 남는지 확인합니다.
     */
    @Test
    void 유일한_PRIMARY의_탈퇴는_그룹과_초대_링크를_함께_종료한다() {
        Fixture f = fixture(true);
        long inviteId = insertInvitation(f.group(), f.actor());

        // 실제 Service를 실행해 1인 그룹 종료의 DB 변경을 확인합니다.
        var result = membershipService.leaveMyGroup(f.actor());
        assertThat(result.groupDeactivated()).isTrue();
        assertThat(membershipStatus(f.actor())).isEqualTo("LEFT");
        assertThat(count("SELECT current_member_count FROM game_groups WHERE id = ?", f.group())).isZero();
        assertThat(jdbc.queryForObject("SELECT status FROM game_groups WHERE id = ?", String.class, f.group()))
                .isEqualTo("INACTIVE");
        assertThat(jdbc.queryForObject("SELECT primary_admin_user_id FROM game_groups WHERE id = ?", Long.class, f.group()))
                .isNull();
        assertThat(count("SELECT COUNT(*) FROM group_invite_links WHERE id = ? AND status = 'INVALIDATED'", inviteId))
                .isEqualTo(1);
        assertThat(count("SELECT COUNT(*) FROM group_join_restrictions WHERE user_id = ?", f.actor()))
                .isEqualTo(1);
        assertInvariants(f.group());
    }

    /**
     * 두 명령이 같은 시점에 출발하도록 조정하고 실제 결과를 수집합니다.
     * 실패 코드가 있는 BusinessException은 결과 문자열로 반환합니다.
     * 기타 예외는 Future.get()에서 테스트 실패로 전파합니다.
     */
    private List<String> race(Callable<?> first, Callable<?> second) throws Exception {
        CountDownLatch start = new CountDownLatch(1);
        Future<String> a = submit(start, first);
        Future<String> b = submit(start, second);

        // 두 요청의 Service 트랜잭션이 독립적으로 진행되게 합니다.
        start.countDown();
        return List.of(a.get(15, TimeUnit.SECONDS), b.get(15, TimeUnit.SECONDS));
    }

    /**
     * 하나의 Service 명령을 별도 작업 스레드에서 실행합니다.
     * 잠금 경쟁이 생겨도 명령 실행을 분리해 실제 DB 락을 사용합니다.
     * 알려진 업무 거부만 오류 코드로 바꾸고 다른 예외는 그대로 전파합니다.
     */
    private Future<String> submit(CountDownLatch start, Callable<?> action) {
        return executor.submit(() -> {
            if (!start.await(5, TimeUnit.SECONDS)) {
                throw new IllegalStateException("STEP 7 시작 신호 대기 시간 초과");
            }
            // 비즈니스 예외 외의 시스템 예외는 테스트 실패로 표시합니다.
            try {
                action.call();
                return "SUCCESS";
            } catch (BusinessException exception) {
                return exception.code();
            }
        });
    }

    /**
     * PRIMARY + MEMBER 두 명 또는 PRIMARY 한 명의 테스트 그룹을 생성합니다.
     * 강퇴 대상은 73시간 미접속 상태로 준비합니다.
     * 그룹 외부의 초대 가입자도 별도 생성하여 경합을 검증할 수 있게 합니다.
     */
    private Fixture fixture(boolean solo) {
        long actor = insertUser("관리자", false);
        long target = insertUser("대상", true);
        long other = insertUser("일반", false);
        long invitee = insertUser("초대가입", false);
        int members = solo ? 1 : 3;

        // 멤버십과 그룹 카운터를 일치시켜 생성합니다.
        String groupName = PREFIX + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        long group = insertId("""
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
                """, groupName, groupName.toLowerCase(Locale.ROOT), members, actor);
        createdGroupIds.add(group);
        insertMembership(group, actor, "PRIMARY");
        if (!solo) {
            insertMembership(group, target, "MEMBER");
            insertMembership(group, other, "MEMBER");
        }
        return new Fixture(group, actor, target, other, invitee);
    }

    /**
     * 테스트 계정과 프로필을 만들고 DB가 발급한 ID를 반환합니다.
     * 대상의 활동 시각만 73시간 전으로 지정해 강퇴 허용 조건을 만듭니다.
     * 사용자 ID는 테스트 종료 시 FK 정리 대상으로 따로 기록합니다.
     */
    private long insertUser(String prefix, boolean inactive) {
        long userId = insertId("""
                INSERT INTO users (
                    status
                  , global_role
                  , last_login_at
                  , last_active_at
                  , version
                  , created_at
                  , updated_at
                ) VALUES (
                    'ACTIVE', 'USER', UTC_TIMESTAMP(6),
                    DATE_SUB(UTC_TIMESTAMP(6), INTERVAL ? HOUR),
                    0, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6)
                )
                """, inactive ? 73 : 0);
        createdUserIds.add(userId);
        String nickname = prefix + userId;

        // 관리자 이전 응답과 구성원 조회에서 UserProfile을 사용합니다.
        jdbc.update("""
                INSERT INTO user_profiles (
                    user_id, nickname, normalized_nickname, version, created_at, updated_at
                ) VALUES (?, ?, ?, 0, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
                """, userId, nickname, nickname.toLowerCase(Locale.ROOT));
        return userId;
    }

    /**
     * 그룹의 활성 멤버십을 INSERT하고 ID 생성은 DB에 맡깁니다.
     * 생성 컬럼 active_user_id·primary_group_id는 DB에서 계산됩니다.
     * 테스트 그룹의 initial memberCount와 동일한 수의 행만 생성해야 합니다.
     */
    private void insertMembership(long groupId, long userId, String role) {
        jdbc.update("""
                INSERT INTO group_memberships (
                    group_id, user_id, role, status, joined_at, version, created_at, updated_at
                ) VALUES (?, ?, ?, 'ACTIVE', UTC_TIMESTAMP(6), 0, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
                """, groupId, userId, role);
    }

    /**
     * 실제 그룹 초대 링크를 발급하고 DB에서 생성한 링크 ID를 반환합니다.
     * 테스트는 원문 토큰을 공개 API로 다루지 않으므로 해시만 고정 생성합니다.
     * active_group_id 생성 컬럼과 UNIQUE 제약은 실제 MySQL에서 검증합니다.
     */
    private long insertInvitation(long groupId, long issuerId) {
        return insertId("""
                INSERT INTO group_invite_links (
                    group_id, token_hash, status, issued_by_user_id, issued_at, created_at
                ) VALUES (?, ?, 'ACTIVE', ?, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
                """, groupId, Hashing.sha256("step7-" + UUID.randomUUID()), issuerId);
    }

    /**
     * INSERT 문을 PreparedStatement로 실행하고 생성된 PK를 반환합니다.
     * 문자열 연결 없이 매개변수를 바인딩해 Fixture를 생성합니다.
     * 기본 연결의 자동 Commit으로 다른 테스트 작업 스레드가 데이터를 확인할 수 있습니다.
     */
    private long insertId(String sql, Object... values) {
        GeneratedKeyHolder key = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            PreparedStatement statement = connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS);
            for (int i = 0; i < values.length; i++) {
                statement.setObject(i + 1, values[i]);
            }
            return statement;
        }, key);
        return Objects.requireNonNull(key.getKey()).longValue();
    }

    /**
     * 그룹 인원·PRIMARY·초대 링크 불변식을 DB에서 직접 확인합니다.
     * 비활성 그룹은 멤버 0명과 PRIMARY NULL, 활성 그룹은 PRIMARY 1명을 요구합니다.
     * 최종값 비교는 Service의 영속성 캐시를 거치지 않습니다.
     */
    private void assertInvariants(long groupId) {
        String groupStatus = jdbc.queryForObject(
                "SELECT status FROM game_groups WHERE id = ?", String.class, groupId);
        int storedCount = count("SELECT current_member_count FROM game_groups WHERE id = ?", groupId);
        int activeCount = count("SELECT COUNT(*) FROM group_memberships WHERE group_id = ? AND status = 'ACTIVE'", groupId);
        int primaryCount = count("""
                SELECT COUNT(*) FROM group_memberships
                 WHERE group_id = ? AND role = 'PRIMARY' AND status = 'ACTIVE'
                """, groupId);
        Long groupPrimary = jdbc.queryForObject(
                "SELECT primary_admin_user_id FROM game_groups WHERE id = ?", Long.class, groupId);
        Long membershipPrimary = jdbc.queryForObject("""
                SELECT MAX(user_id) FROM group_memberships
                 WHERE group_id = ? AND role = 'PRIMARY' AND status = 'ACTIVE'
                """, Long.class, groupId);
        int links = count("SELECT COUNT(*) FROM group_invite_links WHERE group_id = ? AND status = 'ACTIVE'", groupId);

        // 최종 활성 멤버십과 카운터는 반드시 같아야 합니다.
        assertThat(storedCount).isEqualTo(activeCount);
        assertThat(links).isLessThanOrEqualTo(1);
        if ("ACTIVE".equals(groupStatus)) {
            assertThat(primaryCount).isEqualTo(1);
            assertThat(groupPrimary).isNotNull().isEqualTo(membershipPrimary);
        } else {
            assertThat(activeCount).isZero();
            assertThat(primaryCount).isZero();
            assertThat(groupPrimary).isNull();
            assertThat(links).isZero();
        }
    }

    /**
     * 결과 테이블의 숫자 한 건을 조회합니다.
     * 실제 SQL 카운터를 사용해 JPA 캐시의 영향 없이 검증합니다.
     * 테스트에 사용한 PK는 PreparedStatement 인자로 전달합니다.
     */
    private int count(String sql, Object... args) {
        return Objects.requireNonNull(jdbc.queryForObject(sql, Integer.class, args));
    }

    /**
     * 특정 사용자의 최신 멤버십 상태를 조회합니다.
     * 테스트 내 사용자마다 멤버십은 한 개만 생성합니다.
     * 종료 시에도 멤버십을 물리 삭제하지 않는 규칙을 확인할 수 있습니다.
     */
    private String membershipStatus(long userId) {
        return jdbc.queryForObject("SELECT status FROM group_memberships WHERE user_id = ?", String.class, userId);
    }

    /**
     * 활성 사용자만 역할 문자열로 조회합니다.
     * 이미 강퇴된 경우 null을 반환하게 해 사후 상태를 분기합니다.
     * JPA 레벨의 이전 영속성 컨텍스트에 의존하지 않습니다.
     */
    private String activeRole(long userId) {
        return jdbc.queryForObject("""
                SELECT MAX(role) FROM group_memberships
                 WHERE user_id = ? AND status = 'ACTIVE'
                """, String.class, userId);
    }

    private record Fixture(long group, long actor, long target, long other, long invitee) {}
}
