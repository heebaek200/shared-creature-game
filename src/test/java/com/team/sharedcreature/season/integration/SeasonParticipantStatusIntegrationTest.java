package com.team.sharedcreature.season.integration;

import com.team.sharedcreature._core.security.LoginUser;
import com.team.sharedcreature.auth.domain.User;
import com.team.sharedcreature.auth.domain.UserProfile;
import com.team.sharedcreature.auth.infrastructure.UserProfileRepository;
import com.team.sharedcreature.auth.infrastructure.UserRepository;
import com.team.sharedcreature.group.domain.Group;
import com.team.sharedcreature.group.domain.GroupMembership;
import com.team.sharedcreature.group.infrastructure.GroupMembershipRepository;
import com.team.sharedcreature.group.infrastructure.GroupRepository;
import com.team.sharedcreature.group.application.GroupMembershipService;
import com.team.sharedcreature.group.application.GroupRemovalService;
import com.team.sharedcreature.season.application.SeasonCloseFinalizationService;
import com.team.sharedcreature.season.application.SeasonGroupSnapshotService;
import com.team.sharedcreature.season.application.SeasonMemberSnapshotService;
import com.team.sharedcreature.season.application.SeasonTransitionService;
import com.team.sharedcreature.season.domain.GroupCreature;
import com.team.sharedcreature.season.domain.GroupSeason;
import com.team.sharedcreature.season.domain.Season;
import com.team.sharedcreature.season.domain.SeasonGroupScore;
import com.team.sharedcreature.season.domain.SeasonMemberContribution;
import com.team.sharedcreature.season.infrastructure.GroupCreatureRepository;
import com.team.sharedcreature.season.infrastructure.GroupSeasonRepository;
import com.team.sharedcreature.season.infrastructure.SeasonGroupScoreRepository;
import com.team.sharedcreature.season.infrastructure.SeasonMemberContributionRepository;
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
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 실제 MySQL에서 가입·탈퇴·강퇴와 개인 기여도 상태의 일관성을 검증합니다.
 * 종료 처리 후 불변 Snapshot이 실제 참가 상태를 보존하는지도 확인합니다.
 * TEST_DB_USERNAME이 있는 전용 테스트 DB에서만 실행해야 합니다.
 */
@SpringBootTest
@ActiveProfiles("test")
@EnabledIfEnvironmentVariable(named = "TEST_DB_USERNAME", matches = ".+")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class SeasonParticipantStatusIntegrationTest {

    private static final Instant START = Instant.parse("2030-01-06T15:00:00Z");
    private static final Instant END = START.plus(Duration.ofDays(28));
    private static final Instant NOW = START.plus(Duration.ofDays(2));
    private static final long CONTRIBUTION_SCORE = 42L;

    /**
     * 테스트별로 이동 가능한 Clock을 독립 주입합니다.
     * 가입 제한이 해제된 시각과 시즌 만료 시각을 테스트에서 재현합니다.
     * 실제 서버 Clock이나 다른 프로필 설정은 변경하지 않습니다.
     */
    @TestConfiguration
    static class ClockConfiguration {
        @Bean
        @Primary
        AdjustableClock participantStatusClock() {
            return new AdjustableClock(NOW);
        }
    }

    static final class AdjustableClock extends Clock {
        private final AtomicReference<Instant> current;

        AdjustableClock(Instant start) {
            this.current = new AtomicReference<>(start);
        }

        /**
         * 테스트 시각을 지정한 Instant로 변경합니다.
         * 호출되는 업무 Service에는 동일한 Clock 인스턴스가 주입됩니다.
         * 실제 스케줄러는 test 프로필에서 비활성화됩니다.
         */
        void set(Instant now) {
            current.set(now);
        }

        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return Clock.fixed(instant(), zone); }
        @Override public Instant instant() { return current.get(); }
    }

    @Autowired private AdjustableClock clock;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private UserRepository userRepository;
    @Autowired private UserProfileRepository profileRepository;
    @Autowired private GroupRepository groupRepository;
    @Autowired private GroupMembershipRepository membershipRepository;
    @Autowired private SeasonRepository seasonRepository;
    @Autowired private GroupSeasonRepository groupSeasonRepository;
    @Autowired private GroupCreatureRepository creatureRepository;
    @Autowired private SeasonGroupScoreRepository scoreRepository;
    @Autowired private SeasonMemberContributionRepository contributionRepository;
    @Autowired private GroupMembershipService membershipService;
    @Autowired private GroupRemovalService removalService;
    @Autowired private SeasonTransitionService transitionService;
    @Autowired private SeasonGroupSnapshotService groupSnapshotService;
    @Autowired private SeasonMemberSnapshotService memberSnapshotService;
    @Autowired private SeasonCloseFinalizationService finalizationService;

    private Long seasonId;
    private Long groupId;
    private Long groupSeasonId;
    private Long adminId;
    private Long memberId;
    private Long memberMembershipId;
    private Instant scoreReachedAt;

    /**
     * 새 ACTIVE 시즌·활성 그룹·주 관리자·일반 회원을 실제 JPA로 저장합니다.
     * 기존 업무 Service의 가입 메서드를 이용하여 그룹 카운터도 함께 설정합니다.
     * 초기 그룹 시즌에는 실제 0점 점수와 알을 저장합니다.
     */
    @BeforeEach
    void setUp() {
        clock.set(NOW);
        int number = ThreadLocalRandom.current().nextInt(1_500_000_000, 1_800_000_000);

        // 다른 통합 테스트와 번호가 겹치지 않도록 큰 랜덤 번호를 사용합니다.
        Season season = seasonRepository.saveAndFlush(Season.schedule(number, START, END, NOW));
        assertTrue(season.activateAfterInitialization(NOW));
        season = seasonRepository.saveAndFlush(season);
        seasonId = season.getId();

        User admin = userRepository.saveAndFlush(User.register("USER", NOW));
        User member = userRepository.saveAndFlush(User.register("USER", NOW));
        adminId = admin.getId();
        memberId = member.getId();
        profileRepository.saveAndFlush(UserProfile.create(admin, "admin" + adminId, "admin" + adminId, NOW));
        profileRepository.saveAndFlush(UserProfile.create(member, "member" + memberId, "member" + memberId, NOW));

        // 그룹 개설자는 PRIMARY, 추가 회원은 실제 joinGroup으로 가입시킵니다.
        Group group = groupRepository.saveAndFlush(Group.create(
                "참여검증" + adminId, "participant-" + adminId, "test", admin, NOW));
        groupId = group.getId();
        membershipRepository.saveAndFlush(GroupMembership.primary(group, admin, NOW));
        GroupSeason groupSeason = groupSeasonRepository.saveAndFlush(GroupSeason.start(group, season, NOW));
        groupSeasonId = groupSeason.getId();
        creatureRepository.saveAndFlush(GroupCreature.egg(groupSeason, NOW));
        scoreRepository.saveAndFlush(SeasonGroupScore.zero(groupSeason, NOW));

        membershipService.joinGroup(new LoginUser(memberId, "member" + memberId, Set.of()), groupId);
        memberMembershipId = membershipRepository.findByUserIdAndStatus(memberId, "ACTIVE")
                .orElseThrow().getId();
    }

    /**
     * 각 업무 Service가 Commit한 데이터를 외래키 역순으로 삭제합니다.
     * Snapshot 및 강퇴 감사 기록과 가입 제한을 멤버십보다 먼저 삭제합니다.
     * 테스트에서 생성한 ID만 삭제하며 기존 데이터를 직접 수정하지 않습니다.
     */
    @AfterEach
    void tearDown() {
        if (groupId != null) {
            jdbc.update("DELETE FROM moderation_actions WHERE group_id = ?", groupId);
        }
        if (memberId != null) {
            jdbc.update("DELETE FROM group_join_restrictions WHERE user_id = ?", memberId);
        }
        if (seasonId != null) {
            jdbc.update("DELETE FROM season_member_rank_snapshots WHERE season_id = ?", seasonId);
            jdbc.update("DELETE FROM season_group_rank_snapshots WHERE season_id = ?", seasonId);
            jdbc.update("DELETE FROM season_member_contributions WHERE group_season_id IN "
                    + "(SELECT id FROM group_seasons WHERE season_id = ?)", seasonId);
            jdbc.update("DELETE FROM group_creatures WHERE group_season_id IN "
                    + "(SELECT id FROM group_seasons WHERE season_id = ?)", seasonId);
            jdbc.update("DELETE FROM season_group_scores WHERE group_season_id IN "
                    + "(SELECT id FROM group_seasons WHERE season_id = ?)", seasonId);
            jdbc.update("DELETE FROM group_seasons WHERE season_id = ?", seasonId);
        }
        if (groupId != null) {
            jdbc.update("DELETE FROM group_memberships WHERE group_id = ?", groupId);
            jdbc.update("DELETE FROM game_groups WHERE id = ?", groupId);
        }
        if (adminId != null) {
            jdbc.update("DELETE FROM user_profiles WHERE user_id = ?", adminId);
        }
        if (memberId != null) {
            jdbc.update("DELETE FROM user_profiles WHERE user_id = ?", memberId);
        }
        if (adminId != null) {
            jdbc.update("DELETE FROM users WHERE id = ?", adminId);
        }
        if (memberId != null) {
            jdbc.update("DELETE FROM users WHERE id = ?", memberId);
        }
        if (seasonId != null) {
            jdbc.update("DELETE FROM seasons WHERE id = ?", seasonId);
        }
    }

    /**
     * 실제 탈퇴 Service가 개인 기여도를 LEFT로 갱신하는지 검증합니다.
     * 기여도 점수와 도달 시각은 변경 전후에 같아야 합니다.
     * 종료 Snapshot 또한 LEFT로 생성되어야 합니다.
     */
    @Test
    void selfLeavePreservesScoreAndCapturesLeftSnapshot() {
        createContribution();
        membershipService.leaveMyGroup(memberId);

        // 업무 트랜잭션 Commit 후 실제 SQL로 상태·점수·도달 시각을 확인합니다.
        assertEquals("LEFT", membershipStatus(memberMembershipId));
        assertContribution("LEFT", memberMembershipId);
        endSeasonAndSnapshot();
        assertSnapshot("LEFT");
    }

    /**
     * 실제 비활동 강퇴 Service가 REMOVED 상태를 반영하는지 검증합니다.
     * 시즌 점수·개인 기여도 점수는 강퇴로 변경되어서는 안 됩니다.
     * 종료 Snapshot에서도 REMOVED가 고정돼야 합니다.
     */
    @Test
    void inactiveRemovalPreservesScoreAndCapturesRemovedSnapshot() {
        createContribution();
        // 실제 가입 후 96시간 동안 활동하지 않은 상황을 만들고 강퇴합니다.
        clock.set(NOW.plus(Duration.ofDays(4)));
        removalService.removeInactiveMember(adminId, memberId, "장기 미접속");

        // 강퇴 상태는 기존 개인 기여도에 저장되며 Snapshot에 복제됩니다.
        assertEquals("REMOVED", membershipStatus(memberMembershipId));
        assertContribution("REMOVED", memberMembershipId);
        endSeasonAndSnapshot();
        assertSnapshot("REMOVED");
    }

    /**
     * 24시간 재가입 제한이 끝난 뒤 같은 그룹에 가입할 수 있는지 확인합니다.
     * 기존 기여도는 새 멤버십 ID와 ACTIVE 상태로 복구하되 점수를 보존합니다.
     * 그룹 시즌·개인 기여도에는 중복 행을 생성하지 않습니다.
     */
    @Test
    void rejoiningSameGroupRestoresActiveWithoutResettingScore() {
        createContribution();
        membershipService.leaveMyGroup(memberId);
        assertContribution("LEFT", memberMembershipId);

        // 재가입은 탈퇴 후 24시간 제한을 벗어난 동일 시즌 시각에서 실행합니다.
        clock.set(NOW.plus(Duration.ofDays(2)));
        membershipService.joinGroup(new LoginUser(memberId, "member" + memberId, Set.of()), groupId);
        Long newMembershipId = membershipRepository.findByUserIdAndStatus(memberId, "ACTIVE")
                .orElseThrow().getId();

        assertNotEquals(memberMembershipId, newMembershipId);
        assertContribution("ACTIVE", newMembershipId);
        assertEquals(1L, count("SELECT COUNT(*) FROM season_member_contributions WHERE group_season_id = ?", groupSeasonId));
        endSeasonAndSnapshot();
        assertSnapshot("ACTIVE");
    }

    /**
     * 이미 ENDED로 확정한 시즌 Snapshot은 이후 탈퇴로 바뀌면 안 됩니다.
     * 시즌 기간이 지난 후의 탈퇴는 현재 시즌의 기여도를 찾지 않습니다.
     * 과거 Snapshot은 최초 저장한 ACTIVE 참가 상태로 유지합니다.
     */
    @Test
    void departureAfterSeasonEndDoesNotChangeHistoricalSnapshot() {
        createContribution();
        endSeasonAndSnapshot();
        assertSnapshot("ACTIVE");

        // 시즌 종료 이후에도 그룹 탈퇴는 허용하지만 종료 랭킹은 변경하지 않습니다.
        membershipService.leaveMyGroup(memberId);
        assertEquals("LEFT", membershipStatus(memberMembershipId));
        assertContribution("ACTIVE", memberMembershipId);
        assertSnapshot("ACTIVE");
    }

    /**
     * 퀘스트를 완료한 적 없는 회원에게는 기여도 행이 없어야 합니다.
     * 자진 탈퇴할 때 개인 기여도를 소급 생성하지 않습니다.
     * 그룹 점수 또는 알 생성 상태는 영향을 받지 않습니다.
     */
    @Test
    void memberWithoutContributionLeavesNoSyntheticRow() {
        membershipService.leaveMyGroup(memberId);
        assertEquals(0L, count("SELECT COUNT(*) FROM season_member_contributions WHERE group_season_id = ?", groupSeasonId));
        assertEquals("LEFT", membershipStatus(memberMembershipId));
    }

    /**
     * 실제 개인 기여도 행과 그룹 점수를 모두 42점으로 만듭니다.
     * 퀘스트 API 의존 없이 이미 커밋된 랭킹 원본 데이터를 구성합니다.
     * 이후 탈퇴·강퇴 서비스가 점수 원본을 변경하지 않는지 검사합니다.
     */
    private void createContribution() {
        GroupSeason gs = groupSeasonRepository.findById(groupSeasonId).orElseThrow();
        GroupMembership member = membershipRepository.findById(memberMembershipId).orElseThrow();
        User user = userRepository.findById(memberId).orElseThrow();

        // 실제 도메인 팩토리와 점수 증가 메서드를 사용해 원본을 구성합니다.
        SeasonMemberContribution contribution = SeasonMemberContribution.zero(gs, user, member, NOW);
        contribution.addContribution(CONTRIBUTION_SCORE, member, NOW);
        contributionRepository.saveAndFlush(contribution);
        SeasonGroupScore score = scoreRepository.findByGroupSeasonId(groupSeasonId).orElseThrow();
        score.addScore(CONTRIBUTION_SCORE, NOW);
        scoreRepository.saveAndFlush(score);
        scoreReachedAt = NOW;
    }

    /**
     * 현재 그룹의 시즌을 실제 종료 Service로 CLOSING→ENDED 전환합니다.
     * 그룹·개인 Snapshot은 각 독립 REQUIRES_NEW 트랜잭션에서 생성됩니다.
     * 종료 결과는 JDBC를 통해 Commit된 상태로 확인합니다.
     */
    private void endSeasonAndSnapshot() {
        clock.set(END.plusSeconds(1));
        assertTrue(transitionService.beginClosingIfExpired(seasonId));
        assertEquals(0, groupSnapshotService.writeNextBatch(seasonId).remaining());
        assertEquals(0, memberSnapshotService.writeNextBatch(seasonId).remaining());
        assertTrue(finalizationService.finishIfSnapshotsComplete(seasonId));
        assertEquals("ENDED", jdbc.queryForObject(
                "SELECT status FROM seasons WHERE id = ?", String.class, seasonId));
    }

    /**
     * 저장된 기여도와 최신 멤버십의 상태·점수·시각을 확인합니다.
     * JPA 영속성 컨텍스트가 아닌 MySQL 실제 행을 조회합니다.
     * 탈퇴·재가입으로 점수 정보가 재기록되는 회귀를 방지합니다.
     */
    private void assertContribution(String expectedStatus, Long expectedMembershipId) {
        assertEquals(expectedStatus, jdbc.queryForObject(
                "SELECT participant_status FROM season_member_contributions WHERE group_season_id = ? AND user_id = ?",
                String.class, groupSeasonId, memberId));
        assertEquals(Long.valueOf(CONTRIBUTION_SCORE), jdbc.queryForObject(
                "SELECT contribution_score FROM season_member_contributions WHERE group_season_id = ? AND user_id = ?",
                Long.class, groupSeasonId, memberId));
        assertEquals(expectedMembershipId, jdbc.queryForObject(
                "SELECT latest_membership_id FROM season_member_contributions WHERE group_season_id = ? AND user_id = ?",
                Long.class, groupSeasonId, memberId));
        Timestamp reached = jdbc.queryForObject(
                "SELECT score_reached_at FROM season_member_contributions WHERE group_season_id = ? AND user_id = ?",
                Timestamp.class, groupSeasonId, memberId);
        assertNotNull(reached);
        assertEquals(scoreReachedAt, reached.toInstant());
        assertEquals(Long.valueOf(CONTRIBUTION_SCORE), jdbc.queryForObject(
                "SELECT score FROM season_group_scores WHERE group_season_id = ?",
                Long.class, groupSeasonId));
    }

    /**
     * 개인 Snapshot에 최초 반영된 참가 상태가 유지되는지 확인합니다.
     * Snapshot은 그룹 시즌·사용자별로 단 하나여야 합니다.
     * 생성 이후 참가 상태를 수정하는 Service를 사용하지 않습니다.
     */
    private void assertSnapshot(String status) {
        assertEquals(status, jdbc.queryForObject(
                "SELECT participant_status FROM season_member_rank_snapshots WHERE group_season_id = ? AND user_id = ?",
                String.class, groupSeasonId, memberId));
        assertEquals(1L, count("SELECT COUNT(*) FROM season_member_rank_snapshots WHERE group_season_id = ?", groupSeasonId));
    }

    /**
     * PK가 확정된 멤버십의 DB 상태를 조회합니다.
     * JPA 프록시가 아닌 실제 DB의 변경 내용을 확인합니다.
     * 조회 메서드는 결과를 변경하지 않습니다.
     */
    private String membershipStatus(Long membershipId) {
        return jdbc.queryForObject(
                "SELECT status FROM group_memberships WHERE id = ?", String.class, membershipId);
    }

    /**
     * JDBC 집계 결과가 NULL이면 테스트를 실패시킵니다.
     * 테스트에서만 고정한 SQL 문자열과 PK로 조회합니다.
     * DB 내용은 수정하지 않습니다.
     */
    private long count(String sql, Long id) {
        return java.util.Objects.requireNonNull(jdbc.queryForObject(sql, Long.class, id));
    }
}
