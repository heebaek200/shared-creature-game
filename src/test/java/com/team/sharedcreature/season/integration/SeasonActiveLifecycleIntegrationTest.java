package com.team.sharedcreature.season.integration;

import com.team.sharedcreature._core.security.LoginUser;
import com.team.sharedcreature.auth.domain.User;
import com.team.sharedcreature.auth.domain.UserProfile;
import com.team.sharedcreature.auth.infrastructure.UserProfileRepository;
import com.team.sharedcreature.auth.infrastructure.UserRepository;
import com.team.sharedcreature.group.application.GroupMembershipService;
import com.team.sharedcreature.group.domain.Group;
import com.team.sharedcreature.group.domain.GroupMembership;
import com.team.sharedcreature.group.infrastructure.GroupMembershipRepository;
import com.team.sharedcreature.group.infrastructure.GroupRepository;
import com.team.sharedcreature.season.application.SeasonGroupSnapshotService;
import com.team.sharedcreature.season.application.SeasonLifecycleNormalizationService;
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
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 실제 ACTIVE 시즌의 종료 랭킹부터 다음 시즌 ACTIVE 확정까지 검증합니다.
 * 이미 Commit된 그룹 랭킹 Snapshot이 있는 상태에서 재개하는 경우도 확인합니다.
 * test 전용 MySQL에서만 실행하며 운영 코드를 변경하지 않습니다.
 */
@SpringBootTest
@ActiveProfiles("test")
@EnabledIfEnvironmentVariable(named = "TEST_DB_USERNAME", matches = ".+")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class SeasonActiveLifecycleIntegrationTest {

    private static final Instant FIRST_START = Instant.parse("2026-11-01T15:00:00Z");
    private static final Instant FIRST_END = FIRST_START.plus(Duration.ofDays(28));
    private static final Instant OPEN = FIRST_START.plus(Duration.ofDays(1));
    private static final Instant MEMBER_SCORE_FIRST = OPEN.plus(Duration.ofHours(2));
    private static final Instant MEMBER_SCORE_SECOND = OPEN.plus(Duration.ofHours(3));
    private static final Instant GROUP_SCORE_FIRST = OPEN.plus(Duration.ofHours(4));
    private static final Instant GROUP_SCORE_SECOND = OPEN.plus(Duration.ofHours(5));
    private static final Instant DEPARTURE = OPEN.plus(Duration.ofDays(1));
    private static final Instant RECOVERY = FIRST_END.plus(Duration.ofDays(1));

    /**
     * 테스트별로 시각을 전환해 운영 시즌과 종료 후 재기동을 재현합니다.
     * Spring 업무 Service의 Clock은 하나의 가변 인스턴스를 공유합니다.
     * 테스트 밖 환경이나 실제 운영 시간에는 영향을 주지 않습니다.
     */
    @TestConfiguration
    static class TimeConfig {
        @Bean
        @Primary
        AdjustableClock activeLifecycleClock() {
            return new AdjustableClock(OPEN);
        }
    }

    static final class AdjustableClock extends Clock {
        private final AtomicReference<Instant> current;

        AdjustableClock(Instant initial) {
            current = new AtomicReference<>(initial);
        }

        /**
         * 다음 업무 호출이 관찰할 현재 시각을 갱신합니다.
         * 시각 변경은 테스트 스레드에서 업무 호출 사이에만 수행합니다.
         * DB의 저장 시각이나 실제 시스템 시각은 조작하지 않습니다.
         */
        void set(Instant now) {
            current.set(Objects.requireNonNull(now, "now"));
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
    @Autowired private SeasonTransitionService transitionService;
    @Autowired private SeasonGroupSnapshotService groupSnapshotService;
    @Autowired private SeasonLifecycleNormalizationService normalizer;

    private final List<Long> userIds = new ArrayList<>();
    private final List<Long> groupIds = new ArrayList<>();
    private Long firstSeasonId;
    private Long nextSeasonId;
    private Long groupA;
    private Long groupB;
    private Long groupC;
    private Long departedUserId;
    private Long departedMembershipId;
    private Instant zeroScoreCreatedAt;

    /**
     * 실제 운영된 첫 시즌, 그룹 3개와 개인 기여도 3건을 생성합니다.
     * 두 그룹은 동점·서로 다른 도달 시각, 세 번째 그룹은 0점입니다.
     * 한 구성원은 자진 탈퇴시켜 최종 Snapshot 상태도 검증합니다.
     */
    @BeforeEach
    void setUp() {
        clock.set(OPEN);

        // 최초 시즌 시드가 충돌하지 않도록 독립된 test DB 상태를 검증합니다.
        assertEquals(0L, count("SELECT COUNT(*) FROM seasons"),
                "이 테스트는 시즌이 없는 전용 test DB에서 실행해야 합니다.");
        Season season = seasonRepository.saveAndFlush(Season.schedule(1, FIRST_START, FIRST_END, OPEN));
        assertTrue(season.activateAfterInitialization(OPEN));
        season = seasonRepository.saveAndFlush(season);
        firstSeasonId = season.getId();

        User ownerA = newUser("ownerA");
        User memberA = newUser("memberA");
        User ownerB = newUser("ownerB");
        User ownerC = newUser("ownerC");
        departedUserId = memberA.getId();

        // 그룹을 실제 JPA 팩토리로 만들고 각 그룹의 초기 알과 점수를 저장합니다.
        Group a = newGroup(ownerA, "A");
        Group b = newGroup(ownerB, "B");
        Group c = newGroup(ownerC, "C");
        groupA = a.getId();
        groupB = b.getId();
        groupC = c.getId();

        // 실제 가입 Service로 인원을 2명으로 올려 탈퇴 처리도 실제 로직을 이용합니다.
        membershipService.joinGroup(new LoginUser(memberA.getId(), "member" + memberA.getId(), Set.of()), a.getId());
        GroupMembership memberMembership = membershipRepository
                .findByUserIdAndStatus(memberA.getId(), "ACTIVE").orElseThrow();
        departedMembershipId = memberMembership.getId();

        GroupSeason gsA = newGroupSeason(a, season);
        GroupSeason gsB = newGroupSeason(b, season);
        GroupSeason gsC = newGroupSeason(c, season);
        GroupMembership membershipA = membershipRepository.findByUserIdAndStatus(ownerA.getId(), "ACTIVE").orElseThrow();
        GroupMembership membershipB = membershipRepository.findByUserIdAndStatus(ownerB.getId(), "ACTIVE").orElseThrow();

        // 그룹 A의 동점 개인 두 명과 그룹 B의 단독 개인을 준비합니다.
        addContribution(gsA, ownerA, membershipA, 40L, MEMBER_SCORE_FIRST);
        addContribution(gsA, memberA, memberMembership, 40L, MEMBER_SCORE_SECOND);
        addContribution(gsB, ownerB, membershipB, 80L, MEMBER_SCORE_FIRST);
        addGroupScore(gsA, 80L, GROUP_SCORE_FIRST);
        addGroupScore(gsB, 80L, GROUP_SCORE_SECOND);
        zeroScoreCreatedAt = scoreRepository.findByGroupSeasonId(gsC.getId())
                .orElseThrow().getCreatedAt();

        // 시즌 종료 직전 실제 탈퇴 업무를 실행하고 점수·이력의 보존 여부를 확인합니다.
        clock.set(DEPARTURE);
        membershipService.leaveMyGroup(departedUserId);
        assertEquals("LEFT", textValue("SELECT status FROM group_memberships WHERE id = ?", departedMembershipId));
        assertEquals("LEFT", textValue("""
                SELECT participant_status
                  FROM season_member_contributions
                 WHERE group_season_id = ? AND user_id = ?
                """, gsA.getId(), departedUserId));
    }

    /**
     * 독립 Commit된 Snapshot과 신규 시즌 초기화 행을 외래키 역순으로 삭제합니다.
     * 그룹·회원·시즌 ID를 테스트 내부에 기록한 경우만 해당 행을 정리합니다.
     * 테스트 DB 전용이며 운영·개발용 데이터는 삭제하지 않습니다.
     */
    @AfterEach
    void tearDown() {
        // 최초 시드 준비 자체가 실패했다면 다른 테스트의 데이터는 절대 삭제하지 않습니다.
        if (firstSeasonId == null) {
            return;
        }

        // 새 시즌은 정상화 도중 생성된 뒤 테스트 검증 전에 실패할 수 있습니다.
        List<Long> createdSeasonIds = jdbc.queryForList("""
                SELECT id FROM seasons
                 WHERE season_number = 2
                """, Long.class);
        if (!createdSeasonIds.isEmpty()) {
            nextSeasonId = createdSeasonIds.getFirst();
            removeSeasonDependents(nextSeasonId);
            jdbc.update("DELETE FROM seasons WHERE id = ?", nextSeasonId);
        }

        if (firstSeasonId != null) {
            removeSeasonDependents(firstSeasonId);

            // 테스트에서 생성한 시즌의 Scheduler 실행 이력만 삭제합니다.
            // JDBC Timestamp 변환 없이 DB에 저장된 시즌 종료 시각으로 비교합니다.
                        jdbc.update("""
                    DELETE FROM scheduler_executions
                     WHERE job_type = 'SEASON_CLOSE'
                       AND scheduled_for = (
                           SELECT end_at
                             FROM seasons
                            WHERE id = ?
                       )
                    """, firstSeasonId);

        }
        // 탈퇴 제한 행의 멤버십 FK를 그룹 멤버십 삭제 전에 제거합니다.
        for (Long userId : userIds) {
            jdbc.update("DELETE FROM group_join_restrictions WHERE user_id = ?", userId);
        }
        for (Long groupId : groupIds) {
            jdbc.update("DELETE FROM group_memberships WHERE group_id = ?", groupId);
            jdbc.update("DELETE FROM game_groups WHERE id = ?", groupId);
        }
        for (Long userId : userIds) {
            jdbc.update("DELETE FROM user_profiles WHERE user_id = ?", userId);
            jdbc.update("DELETE FROM users WHERE id = ?", userId);
        }
        if (firstSeasonId != null) {
            jdbc.update("DELETE FROM seasons WHERE id = ?", firstSeasonId);
        }
    }

    /**
     * ACTIVE 시즌 종료 전체 흐름과 신규 시즌의 알·점수 초기화를 검증합니다.
     * 그룹·개인의 동점 순서, 0점 대체 시각, 탈퇴 상태가 Snapshot에 보존되어야 합니다.
     * 같은 시각에 정상화를 재실행해도 Snapshot과 시즌 정보가 변하지 않아야 합니다.
     */
    @Test
    void activeSeasonClosesAndNextSeasonActivatesIdempotently() {
        clock.set(RECOVERY);
        SeasonLifecycleNormalizationService.Result result = normalizer.normalize();

        assertEquals(
                SeasonLifecycleNormalizationService.Outcome.ACTIVE,
                result.outcome(),
                () -> """
                시즌 정상화 실패
                result: %s
                seasons: %s
                scheduler_executions: %s
                """.formatted(
                        result,
                        jdbc.queryForList("""
                        SELECT id, status, closing_from_status
                          FROM seasons
                         ORDER BY id
                        """),
                        jdbc.queryForList("""
                        SELECT status
                             , attempt_count
                             , error_code
                             , error_detail
                          FROM scheduler_executions
                         WHERE job_type = 'SEASON_CLOSE'
                           AND scheduled_for = ?
                        """, Timestamp.from(FIRST_END))
                )
        );

        nextSeasonId = Objects.requireNonNull(result.seasonId());

        // ACTIVE 출처 종료는 시즌 상태뿐 아니라 그룹·개인 순위도 모두 기록해야 합니다.
        assertEquals("ENDED", textValue("SELECT status FROM seasons WHERE id = ?", firstSeasonId));
        assertEquals("ACTIVE", textValue("SELECT status FROM seasons WHERE id = ?", nextSeasonId));
        assertEquals(3L, count("SELECT COUNT(*) FROM season_group_rank_snapshots WHERE season_id = ?", firstSeasonId));
        assertEquals(3L, count("SELECT COUNT(*) FROM season_member_rank_snapshots WHERE season_id = ?", firstSeasonId));

        System.out.println("EXPECTED FIRST_END = " + FIRST_END);

        System.out.println("SCHEDULER EXECUTIONS = " +
                jdbc.queryForList("""
                SELECT id
                     , job_type
                     , status
                     , scheduled_for
                     , attempt_count
                     , error_code
                     , error_detail
                  FROM scheduler_executions
                 ORDER BY id
                """)
        );


        assertEquals(1L, count("""
        SELECT COUNT(*)
          FROM scheduler_executions e
          JOIN seasons s ON s.end_at = e.scheduled_for
         WHERE e.job_type = 'SEASON_CLOSE'
           AND e.status = 'SUCCEEDED'
           AND s.id = ?
        """, firstSeasonId));


        // 같은 80점 그룹의 순위는 점수 도달 시각이 빠른 A가 1위입니다.
        assertEquals(1, intValue("SELECT rank_number FROM season_group_rank_snapshots "
                + "WHERE season_id = ? AND group_id = ?", firstSeasonId, groupA));
        assertEquals(2, intValue("SELECT rank_number FROM season_group_rank_snapshots "
                + "WHERE season_id = ? AND group_id = ?", firstSeasonId, groupB));
        assertEquals(3, intValue("SELECT rank_number FROM season_group_rank_snapshots "
                + "WHERE season_id = ? AND group_id = ?", firstSeasonId, groupC));

        String expectedScoreReachedAt =
                DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSSSSS")
                        .withZone(ZoneOffset.UTC)
                        .format(zeroScoreCreatedAt);

        // JDBC Timestamp 변환 없이 DB에 저장된 UTC 날짜·시간을 비교합니다.
        // 0점 그룹의 Snapshot 도달 시각은 원본 점수 행 생성 시각과 같아야 합니다.
        assertEquals(expectedScoreReachedAt, textValue("""
        SELECT DATE_FORMAT(
                   score_reached_at,
                   '%Y-%m-%d %H:%i:%s.%f'
               )
          FROM season_group_rank_snapshots
         WHERE season_id = ?
           AND group_id = ?
        """, firstSeasonId, groupC));


        // 먼저 40점에 도달한 회원이 개인 1위이며, 탈퇴 회원은 2위·LEFT로 고정됩니다.
        assertEquals(1, intValue("""
                SELECT rank_number FROM season_member_rank_snapshots
                 WHERE season_id = ? AND group_id = ? AND contribution_score = 40
                   AND participant_status = 'ACTIVE'
                """, firstSeasonId, groupA));
        assertEquals(2, intValue("""
                SELECT rank_number FROM season_member_rank_snapshots
                 WHERE season_id = ? AND user_id = ?
                """, firstSeasonId, departedUserId));
        assertEquals("LEFT", textValue("""
                SELECT participant_status FROM season_member_rank_snapshots
                 WHERE season_id = ? AND user_id = ?
                """, firstSeasonId, departedUserId));

        // 신규 시즌의 세 활성 그룹은 모두 새 알과 0점 집계를 가지며 종료 점수는 복제되지 않습니다.
        assertEquals(3L, count("SELECT COUNT(*) FROM group_seasons WHERE season_id = ?", nextSeasonId));
        assertEquals(3L, count("""
                SELECT COUNT(*) FROM group_creatures gc
                  JOIN group_seasons gs ON gs.id = gc.group_season_id
                 WHERE gs.season_id = ? AND gc.growth_stage = 'EGG'
                """, nextSeasonId));
        assertEquals(3L, count("""
                SELECT COUNT(*) FROM season_group_scores s
                  JOIN group_seasons gs ON gs.id = s.group_season_id
                 WHERE gs.season_id = ? AND s.score = 0
                """, nextSeasonId));

        // 신규 시즌의 모든 그룹이 생성 시각부터 72시간의 선택 기한을 가집니다.
        // DB 내부의 날짜 연산으로 비교하여 JDBC 시간대 변환을 피합니다.
        assertEquals(3L, count("""
                SELECT COUNT(*)
                  FROM group_seasons
                 WHERE season_id = ?
                   AND selection_deadline_at = DATE_ADD(
                       created_at,
                       INTERVAL 72 HOUR
                   )
                """, nextSeasonId));


        Map<Long, SnapshotStamp> groupSnapshots = groupStamps();
        Map<Long, SnapshotStamp> memberSnapshots = memberStamps();
        SeasonLifecycleNormalizationService.Result again = normalizer.normalize();
        assertEquals(SeasonLifecycleNormalizationService.Outcome.ACTIVE, again.outcome());
        assertEquals(nextSeasonId, again.seasonId());
        assertEquals(groupSnapshots, groupStamps());
        assertEquals(memberSnapshots, memberStamps());
        assertEquals(1L, count("SELECT COUNT(*) FROM seasons WHERE season_number = 2"));
    }

    /**
     * 그룹 랭킹만 저장한 뒤 중단된 CLOSING 상태의 이어하기를 검증합니다.
     * 재실행해도 기존 그룹 Snapshot ID·생성시각을 그대로 유지해야 합니다.
     * 누락된 개인 Snapshot만 생성한 후 ENDED 및 새 시즌 ACTIVE로 진행합니다.
     */
    @Test
    void committedGroupSnapshotsSurviveInterruptedClosing() {
        clock.set(RECOVERY);
        assertTrue(transitionService.beginClosingIfExpired(firstSeasonId));
        SeasonGroupSnapshotService.Progress firstBatch = groupSnapshotService.writeNextBatch(firstSeasonId);
        assertEquals(3, firstBatch.total());
        assertEquals(3, firstBatch.inserted());
        assertEquals(0, firstBatch.remaining());
        assertEquals("CLOSING", textValue("SELECT status FROM seasons WHERE id = ?", firstSeasonId));
        assertEquals(0L, count("SELECT COUNT(*) FROM season_member_rank_snapshots WHERE season_id = ?", firstSeasonId));
        Map<Long, SnapshotStamp> committed = groupStamps();

        // 프로세스 중단과 동일하게 정상화 오케스트레이터만 다시 호출합니다.
        SeasonLifecycleNormalizationService.Result resumed = normalizer.normalize();
        assertEquals(SeasonLifecycleNormalizationService.Outcome.ACTIVE, resumed.outcome());
        nextSeasonId = Objects.requireNonNull(resumed.seasonId());
        assertEquals("ENDED", textValue("SELECT status FROM seasons WHERE id = ?", firstSeasonId));
        assertEquals(committed, groupStamps());
        assertEquals(3L, count("SELECT COUNT(*) FROM season_member_rank_snapshots WHERE season_id = ?", firstSeasonId));
        assertEquals("LEFT", textValue("""
                SELECT participant_status FROM season_member_rank_snapshots
                 WHERE season_id = ? AND user_id = ?
                """, firstSeasonId, departedUserId));
        assertEquals("ACTIVE", textValue("SELECT status FROM seasons WHERE id = ?", nextSeasonId));
    }

    /**
     * 프로필이 존재하는 테스트 사용자를 실제 JPA로 생성합니다.
     * 표시 닉네임은 사용자 PK를 포함해 다른 사용자와 중복되지 않습니다.
     * 생성한 모든 ID는 테스트 종료 시 외래키 역순 삭제에 사용합니다.
     */
    private User newUser(String prefix) {
        User user = userRepository.saveAndFlush(User.register("USER", OPEN));
        userIds.add(user.getId());
        String nickname = prefix + user.getId();
        profileRepository.saveAndFlush(UserProfile.create(user, nickname, nickname.toLowerCase(), OPEN));
        return user;
    }

    /**
     * 주 관리자와 그룹을 생성하고 나중에 재사용할 그룹 PK를 기록합니다.
     * 회원 수를 임의 SQL로 변경하지 않고 정상적인 멤버십 팩토리를 사용합니다.
     * 시즌 참여·점수·알은 별도 공통 보조 메서드에서 준비합니다.
     */
    private Group newGroup(User admin, String suffix) {
        Group group = groupRepository.saveAndFlush(Group.create(
                "종료통합" + suffix + admin.getId(),
                "close-int-" + suffix.toLowerCase() + "-" + admin.getId(),
                "active season lifecycle test", admin, OPEN
        ));
        groupIds.add(group.getId());
        membershipRepository.saveAndFlush(GroupMembership.primary(group, admin, OPEN));
        return group;
    }

    /**
     * 운영 중인 그룹의 시즌 참여와 알·0점 점수를 실제 DB에 저장합니다.
     * GroupSeason은 새 시즌에도 같은 팩토리가 사용되는지 대조 기준입니다.
     * 그룹 점수 합산 전에는 도달 시각이 NULL인 정상 0점 상태를 유지합니다.
     */
    private GroupSeason newGroupSeason(Group group, Season season) {
        GroupSeason gs = groupSeasonRepository.saveAndFlush(GroupSeason.start(group, season, OPEN));
        creatureRepository.saveAndFlush(GroupCreature.egg(gs, OPEN));
        scoreRepository.saveAndFlush(SeasonGroupScore.zero(gs, OPEN));
        return gs;
    }

    /**
     * 실제 기여도 엔티티의 점수 증가 메서드를 통해 랭킹 원본을 준비합니다.
     * 동일 그룹 시즌의 순위 도달 시각을 서로 다르게 만들어 동점 순서를 확인합니다.
     * 가입 이력과 참가 상태는 기존 도메인 팩토리 그대로 사용합니다.
     */
    private void addContribution(GroupSeason gs, User user, GroupMembership membership, long amount, Instant at) {
        SeasonMemberContribution contribution = SeasonMemberContribution.zero(gs, user, membership, OPEN);
        contribution.addContribution(amount, membership, at);
        contributionRepository.saveAndFlush(contribution);
    }

    /**
     * 시즌 그룹 점수의 양수 도달 시각을 설정합니다.
     * 그룹 간 동점 순위와 0점 대체 시각 처리 경로를 함께 검증합니다.
     * 점수를 직접 SQL로 쓰지 않고 도메인 규칙을 적용합니다.
     */
    private void addGroupScore(GroupSeason gs, long amount, Instant at) {
        SeasonGroupScore score = scoreRepository.findByGroupSeasonId(gs.getId()).orElseThrow();
        score.addScore(amount, at);
        scoreRepository.saveAndFlush(score);
    }

    /**
     * 특정 시즌에 종속된 기록을 외래키 순서로 삭제합니다.
     * 실험에서 생성한 크리처 경험치·퀘스트 관련 기록이 없다는 전제로 사용합니다.
     * 최종 시즌 행 자체는 호출자가 연결 관계를 정리한 다음 삭제합니다.
     */
    private void removeSeasonDependents(Long seasonId) {
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

    /**
     * 실제 DB에서 저장한 그룹 랭킹 Snapshot의 식별자·생성 시각을 조회합니다.
     * 재실행 시 동일한 Snapshot 행이 보존되는지 비교하는 데 사용합니다.
     * 순위 및 점수 변경은 테스트 본문에서 별도 확인합니다.
     */
    private Map<Long, SnapshotStamp> groupStamps() {
        Map<Long, SnapshotStamp> result = new LinkedHashMap<>();
        jdbc.query("""
                SELECT group_id, id, created_at
                  FROM season_group_rank_snapshots
                 WHERE season_id = ?
                 ORDER BY group_id
                """, rs -> {
            result.put(rs.getLong("group_id"), new SnapshotStamp(
                    rs.getLong("id"), rs.getTimestamp("created_at").toInstant()));
        }, firstSeasonId);
        return result;
    }

    /**
     * 실제 DB에 저장된 개인 랭킹 Snapshot의 식별자·생성 시각을 조회합니다.
     * 재실행 전후에 기존 회원·탈퇴자 모두 동일한 Snapshot을 유지해야 합니다.
     * 참가 상태와 순위의 상세 검증은 테스트 본문에서 수행합니다.
     */
    private Map<Long, SnapshotStamp> memberStamps() {
        Map<Long, SnapshotStamp> result = new LinkedHashMap<>();
        jdbc.query("""
                SELECT user_id, id, created_at
                  FROM season_member_rank_snapshots
                 WHERE season_id = ?
                 ORDER BY user_id
                """, rs -> {
            result.put(rs.getLong("user_id"), new SnapshotStamp(
                    rs.getLong("id"), rs.getTimestamp("created_at").toInstant()));
        }, firstSeasonId);
        return result;
    }

    /**
     * 매개변수가 있는 COUNT SQL의 결과를 Long으로 반환합니다.
     * MySQL에 실제 Commit된 행만 조회하며 영속성 컨텍스트에 의존하지 않습니다.
     * 누락된 COUNT 결과는 테스트 실패로 처리합니다.
     */
    private long count(String sql, Object... parameters) {
        return Objects.requireNonNull(jdbc.queryForObject(sql, Long.class, parameters));
    }

    /**
     * 단일 문자열 컬럼을 DB에서 직접 읽습니다.
     * JPA 캐시가 아닌 최신 저장 상태를 관찰합니다.
     * 검색 결과가 없으면 예외를 통해 테스트 실패를 알립니다.
     */
    private String textValue(String sql, Object... parameters) {
        return Objects.requireNonNull(jdbc.queryForObject(sql, String.class, parameters));
    }

    /**
     * 단일 정수 컬럼을 DB에서 직접 읽습니다.
     * 랭킹 번호가 의도한 연속 순위인지 확인할 때 사용합니다.
     * DB 결과가 NULL이면 테스트를 실패시킵니다.
     */
    private int intValue(String sql, Object... parameters) {
        return Objects.requireNonNull(jdbc.queryForObject(sql, Integer.class, parameters));
    }

    /**
     * MySQL DATETIME 시각을 UTC Instant로 변환해 반환합니다.
     * 0점 그룹의 Snapshot 시각이 점수 행 생성 시각과 같은지 검사합니다.
     * 이전 타임존 검증 단계에서 통과한 test 연결을 사용합니다.
     */
    private Instant timestampValue(String sql, Object... parameters) {
        return Objects.requireNonNull(jdbc.queryForObject(sql, Timestamp.class, parameters)).toInstant();
    }

    /**
     * 불변 Snapshot의 PK와 생성 시각을 함께 보존하는 비교용 레코드입니다.
     * 순위 계산 단계가 다시 실행되더라도 동일한 행인지 검증합니다.
     * 엔티티 영속성 컨텍스트와는 독립적으로 비교합니다.
     */
    private record SnapshotStamp(long id, Instant createdAt) {}
}
