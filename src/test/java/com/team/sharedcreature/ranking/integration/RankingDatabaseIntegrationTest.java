package com.team.sharedcreature.ranking.integration;

import com.team.sharedcreature.auth.domain.User;
import com.team.sharedcreature.auth.domain.UserProfile;
import com.team.sharedcreature.group.domain.Group;
import com.team.sharedcreature.group.domain.GroupMembership;
import com.team.sharedcreature.ranking.application.RankingService;
import com.team.sharedcreature.season.domain.*;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContextInitializer;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.annotation.Transactional;

import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 실제 MySQL·JPA·RankingService를 연결해 조회 쿼리와 확정 기록 보존을 검증합니다.
 * 테스트 데이터는 각 테스트 종료 시 롤백하며 공유 테이블을 비우지 않습니다.
 * DB 설정 누락이나 접속 실패를 테스트 성공 또는 건너뜀으로 처리하지 않습니다.
 */
@SpringBootTest(properties = {
        "spring.jpa.hibernate.ddl-auto=update",
        "spring.sql.init.mode=always",
        "app.scheduler.enabled=false"
})
@ActiveProfiles("test")
@ContextConfiguration(initializers = RankingDatabaseIntegrationTest.TestDatabaseOnly.class)
@Transactional
class RankingDatabaseIntegrationTest {

    private static final Instant NOW = Instant.now().truncatedTo(ChronoUnit.MICROS);
    @Autowired private EntityManager em;
    @Autowired private RankingService service;
    private Season season;
    private User owner;
    private Group group;
    private GroupSeason groupSeason;

    @BeforeEach
    void setUp() {
        // 기존 시즌을 수정하지 않고 고유한 시즌 번호·시작 시각을 사용합니다.
        int number = em.createQuery("select coalesce(max(s.seasonNumber), 0) from Season s", Integer.class)
                .getSingleResult() + 1;
        Instant start = NOW.minusSeconds(60).plusNanos(number * 1000L);
        season = Season.schedule(number, start, start.plus(Duration.ofDays(28)), NOW);
        season.activateAfterInitialization(NOW);
        em.persist(season);
        owner = user();
        group = newGroup(owner);
        em.persist(GroupMembership.primary(group, owner, NOW));
        groupSeason = GroupSeason.start(group, season, NOW);
        em.persist(groupSeason);
    }

    @Test
    void 개인_Query는_탈퇴와_강퇴기록을_포함하고_다른그룹을_제외한다() {
        User first = user();
        User second = user();
        User third = user();
        contribution(first, 100, NOW.minusSeconds(10), "LEFT");
        contribution(second, 100, NOW.minusSeconds(10), "REMOVED");
        contribution(third, 100, NOW.minusSeconds(5), "ACTIVE");
        contribution(owner, 20, NOW, "ACTIVE");
        // 별도 그룹의 더 높은 점수가 개인 랭킹에 섞이면 안 됩니다.
        User outsider = user();
        Group otherGroup = newGroup(outsider);
        GroupSeason otherSeason = GroupSeason.start(otherGroup, season, NOW);
        em.persist(otherSeason);
        GroupMembership otherMembership = GroupMembership.primary(otherGroup, outsider, NOW);
        em.persist(otherMembership);
        var other = SeasonMemberContribution.zero(otherSeason, outsider, otherMembership, NOW);
        other.addContribution(999, otherMembership, NOW);
        em.persist(other);
        flushAndClear();

        var firstPage = service.getMemberRanking(owner.getId(), season.getId(), 0, 2);
        var secondPage = service.getMemberRanking(owner.getId(), season.getId(), 1, 2);

        assertThat(firstPage.data()).extracting(row -> row.userId())
                .containsExactly(first.getId(), second.getId());
        assertThat(firstPage.data()).extracting(row -> row.participantStatus())
                .containsExactly("LEFT", "REMOVED");
        assertThat(secondPage.data()).extracting(row -> row.userId())
                .containsExactly(third.getId(), owner.getId());
        assertThat(secondPage.data()).extracting(row -> row.rank()).containsExactly(3, 4);
        assertThat(firstPage.meta().totalElements()).isEqualTo(4);
        assertThat(firstPage.meta().currentSubject().rank()).isEqualTo(4);
        assertThat(firstPage.meta().currentSubject().score()).isEqualTo(20);
    }

    @ParameterizedTest
    @CsvSource({"0,2,2,1", "1,2,1,3", "2,2,0,0", "2147483647,50,0,0"})
    void 실제_조회결과의_페이지경계와_현재대상을_유지한다(
            int page, int size, int expectedSize, int expectedFirstRank
    ) {
        contribution(user(), 100, NOW, "LEFT");
        contribution(user(), 50, NOW, "REMOVED");
        contribution(owner, 10, NOW, "ACTIVE");
        flushAndClear();
        var response = service.getMemberRanking(owner.getId(), season.getId(), page, size);
        assertThat(response.data()).hasSize(expectedSize);
        if (expectedSize > 0) {
            assertThat(response.data().getFirst().rank()).isEqualTo(expectedFirstRank);
        }
        assertThat(response.meta().totalElements()).isEqualTo(3);
        assertThat(response.meta().totalPages()).isEqualTo((3 + size - 1) / size);
        assertThat(response.meta().currentSubject().subjectId()).isEqualTo(owner.getId());
        assertThat(response.meta().currentSubject().rank()).isEqualTo(3);
    }

    @Test
    void 그룹_Query는_동점과_0점시각을_정렬하고_개인탈퇴점수를_재합산하지_않는다() {
        Group first = newGroup(user());
        Group second = newGroup(user());
        Group earlier = newGroup(user());
        score(first, 100, NOW, NOW.minusSeconds(40));
        score(second, 100, NOW, NOW.minusSeconds(40));
        score(earlier, 100, NOW.minusSeconds(1), NOW.minusSeconds(40));
        score(group, 0, null, NOW.minusSeconds(30));
        Group laterZero = newGroup(user());
        score(laterZero, 0, null, NOW.minusSeconds(20));
        flushAndClear();

        var response = service.getGroupRanking(owner.getId(), season.getId(), 0, 3);
        assertThat(response.data()).extracting(row -> row.groupId())
                .containsExactly(earlier.getId(), first.getId(), second.getId());
        assertThat(response.meta().totalElements()).isEqualTo(5);
        assertThat(response.meta().currentSubject().rank()).isEqualTo(4);
        var secondPage = service.getGroupRanking(owner.getId(), season.getId(), 1, 3);
        assertThat(secondPage.data().getFirst().scoreReachedAt()).isEqualTo(NOW.minusSeconds(30));
        // 조회가 0점 원본 행을 갱신하지 않았는지 실제 DB 값으로 확인합니다.
        Object reachedAt = em.createNativeQuery("select score_reached_at from season_group_scores where group_season_id = :id")
                .setParameter("id", groupSeason.getId()).getSingleResult();
        assertThat(reachedAt).isNull();
    }

    @Test
    void 종료개인_Snapshot은_원본프로필과_점수변경후에도_유지된다() {
        SeasonMemberContribution original = contribution(owner, 80, NOW.minusSeconds(10), "ACTIVE");
        UserProfile profile = em.createQuery("select p from UserProfile p where p.user.id = :id", UserProfile.class)
                .setParameter("id", owner.getId()).getSingleResult();
        em.persist(SeasonMemberRankSnapshot.capture(season, groupSeason, original, profile,
                new SeasonRankingCalculator.MemberRank(1, owner.getId(), 80, NOW.minusSeconds(10)), NOW));
        ReflectionTestUtils.setField(season, "status", "ENDED");
        flushAndClear();
        var before = service.getMemberRanking(owner.getId(), season.getId(), 0, 20);

        em.createQuery("update UserProfile p set p.nickname = :name where p.user.id = :id")
                .setParameter("name", "변경한닉네임").setParameter("id", owner.getId()).executeUpdate();
        em.createQuery("update SeasonMemberContribution c set c.contributionScore = 999, c.participantStatus = 'REMOVED' where c.id = :id")
                .setParameter("id", original.getId()).executeUpdate();
        flushAndClear();

        var after = service.getMemberRanking(owner.getId(), season.getId(), 0, 20);
        assertThat(after).isEqualTo(before);
        assertThat(after.data().getFirst().contributionScore()).isEqualTo(80);
        assertThat(after.data().getFirst().participantStatus()).isEqualTo("ACTIVE");
        assertThat(after.data().getFirst().nickname()).isNotEqualTo("변경한닉네임");
        assertThat(em.createQuery("select s from SeasonMemberRankSnapshot s where s.groupSeason.id = :id", SeasonMemberRankSnapshot.class)
                .setParameter("id", groupSeason.getId()).getSingleResult().getCreatedAt()).isEqualTo(NOW);
    }

    @Test
    void 종료그룹_Snapshot은_현재그룹명과_점수변경후에도_확정값을_반환한다() {
        score(group, 120, NOW.minusSeconds(10), NOW.minusSeconds(30));
        em.persist(SeasonGroupRankSnapshot.capture(season, groupSeason,
                new SeasonRankingCalculator.GroupRank(1, group.getId(), 120, NOW.minusSeconds(10)), NOW));
        ReflectionTestUtils.setField(season, "status", "ENDED");
        flushAndClear();
        var before = service.getGroupRanking(owner.getId(), season.getId(), 0, 20);
        em.createQuery("update Group g set g.name = :name where g.id = :id")
                .setParameter("name", "변경한그룹명").setParameter("id", group.getId()).executeUpdate();
        em.createQuery("update SeasonGroupScore s set s.score = 999 where s.groupSeason.id = :id")
                .setParameter("id", groupSeason.getId()).executeUpdate();
        flushAndClear();
        var after = service.getGroupRanking(owner.getId(), season.getId(), 0, 20);
        assertThat(after).isEqualTo(before);
        assertThat(after.data().getFirst().score()).isEqualTo(120);
        assertThat(after.data().getFirst().groupName()).isNotEqualTo("변경한그룹명");
        assertThat(after.meta().currentSubject().rank()).isEqualTo(1);
    }

    @Test
    void 조회시즌에_현재그룹이나_사용자기록이_없으면_현재대상은_null이다() {
        User newcomer = user();
        em.persist(GroupMembership.member(group, newcomer, NOW));
        contribution(owner, 10, NOW, "ACTIVE");
        flushAndClear();
        assertThat(service.getMemberRanking(newcomer.getId(), season.getId(), 0, 20)
                .meta().currentSubject()).isNull();
        assertThat(service.getGroupRanking(newcomer.getId(), season.getId(), 0, 20)
                .meta().currentSubject()).isNull();
    }

    /** 각 사용자는 고유 프로필과 함께 생성하여 실제 닉네임 일괄 조회를 실행합니다. */
    private User user() {
        User user = User.register("USER", NOW);
        em.persist(user);
        String nickname = "랭킹" + UUID.randomUUID().toString().substring(0, 10);
        em.persist(UserProfile.create(user, nickname, nickname, NOW));
        return user;
    }

    private Group newGroup(User admin) {
        String name = "랭킹" + UUID.randomUUID().toString().substring(0, 12);
        Group result = Group.create(name, name, "랭킹 통합 테스트", admin, NOW);
        em.persist(result);
        return result;
    }

    private SeasonMemberContribution contribution(User user, long amount, Instant reachedAt, String status) {
        GroupMembership membership;
        if (user.getId().equals(owner.getId())) {
            membership = em.createQuery("select m from GroupMembership m where m.user.id = :id and m.status = 'ACTIVE'", GroupMembership.class)
                    .setParameter("id", owner.getId()).getSingleResult();
        } else {
            membership = GroupMembership.member(group, user, NOW.minusSeconds(60));
            if (!"ACTIVE".equals(status)) {
                ReflectionTestUtils.setField(membership, "status", status);
                ReflectionTestUtils.setField(membership, "endedAt", NOW);
            }
            em.persist(membership);
        }
        var result = SeasonMemberContribution.zero(groupSeason, user, membership, reachedAt);
        if (amount > 0) {
            result.addContribution(amount, membership, reachedAt);
        }
        result.synchronizeMembership(membership, NOW);
        em.persist(result);
        return result;
    }

    private void score(Group target, long amount, Instant reachedAt, Instant createdAt) {
        GroupSeason targetSeason = target.getId().equals(group.getId()) ? groupSeason
                : GroupSeason.start(target, season, NOW);
        if (targetSeason != groupSeason) {
            em.persist(targetSeason);
        }
        var result = SeasonGroupScore.zero(targetSeason, createdAt);
        if (amount > 0) {
            result.addScore(amount, reachedAt);
        }
        em.persist(result);
    }

    /** 실제 DB에서 다시 읽도록 변경을 반영한 뒤 1차 캐시를 비웁니다. */
    private void flushAndClear() {
        em.flush();
        em.clear();
    }

    /** 접속 전에 전용 테스트 DB명을 확인하여 개발 DB의 DDL과 데이터 변경을 차단합니다. */
    public static class TestDatabaseOnly implements ApplicationContextInitializer<ConfigurableApplicationContext> {
        @Override
        public void initialize(ConfigurableApplicationContext context) {
            String url = context.getEnvironment().getProperty("spring.datasource.url", "");
            boolean testDatabase = false;
            try {
                testDatabase = url.startsWith("jdbc:mysql:")
                        && "/shared_creature_game_test".equals(URI.create(url.substring(5)).getPath());
            } catch (IllegalArgumentException ignored) {
                // 접속 문자열이나 인증 정보는 오류 메시지에 출력하지 않습니다.
            }
            if (!testDatabase) {
                throw new IllegalStateException("랭킹 DB 테스트는 shared_creature_game_test 전용 DB가 필요합니다.");
            }
        }
    }
}
