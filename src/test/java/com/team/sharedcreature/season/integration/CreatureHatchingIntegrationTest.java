package com.team.sharedcreature.season.integration;

import com.team.sharedcreature.auth.domain.User;
import com.team.sharedcreature.auth.infrastructure.UserRepository;
import com.team.sharedcreature.group.domain.Group;
import com.team.sharedcreature.group.domain.GroupMembership;
import com.team.sharedcreature.group.infrastructure.GroupMembershipRepository;
import com.team.sharedcreature.group.infrastructure.GroupRepository;
import com.team.sharedcreature.season.application.CreatureHatchingNormalizationService;
import com.team.sharedcreature.season.application.CreatureSelectionService;
import com.team.sharedcreature.season.application.SeasonTransitionService;
import com.team.sharedcreature.season.api.CreatureSelectionResponse;
import com.team.sharedcreature.season.domain.GroupCreature;
import com.team.sharedcreature.season.domain.GroupSeason;
import com.team.sharedcreature.season.domain.Season;
import com.team.sharedcreature.season.infrastructure.GroupCreatureRepository;
import com.team.sharedcreature.season.infrastructure.GroupSeasonRepository;
import com.team.sharedcreature.season.infrastructure.SeasonRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
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
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 4-G-2: 실제 MySQL의 잠금·Commit과 기존 부화 업무 서비스의 경계를 검증합니다.
 * 72시간 경계, 관리자 종 선택 보존, 알 경험치, 중복 실행과 복구를 확인합니다.
 * 테스트용 빈 MySQL DB에서만 실행하며 운영 DB의 기존 자료는 삭제하지 않습니다.
 */
@SpringBootTest
@ActiveProfiles("test")
@EnabledIfEnvironmentVariable(named = "TEST_DB_USERNAME", matches = ".+")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class CreatureHatchingIntegrationTest {

    private static final Instant START = Instant.parse("2026-11-01T15:00:00Z");
    private static final Instant END = START.plus(Duration.ofDays(28));
    private static final Instant CREATED = START.plus(Duration.ofDays(1));
    private static final Instant DEADLINE = CREATED.plus(Duration.ofHours(72));

    @TestConfiguration
    static class ClockConfiguration {
        /**
         * 테스트 대상 업무 서비스가 동일한 시각을 사용하게 합니다.
         * MySQL의 실제 현재 시각을 바꾸지 않고 Spring의 Clock만 교체합니다.
         * 별도의 Scheduler Bean은 test 프로필에서 비활성화된 상태입니다.
         */
        @Bean
        @Primary
        AdjustableClock creatureHatchingTestClock() {
            return new AdjustableClock(CREATED);
        }
    }

    /**
     * 시각 경계 검증을 위해 자유롭게 이동할 수 있는 테스트 전용 Clock입니다.
     * 여러 작업 스레드가 동시에 읽더라도 완전한 Instant 하나만 관찰합니다.
     * JVM 전역 시간대나 실제 시스템 시각을 수정하지 않습니다.
     */
    static final class AdjustableClock extends Clock {
        private final AtomicReference<Instant> value;

        AdjustableClock(Instant initial) {
            value = new AtomicReference<>(Objects.requireNonNull(initial));
        }

        void set(Instant instant) {
            value.set(Objects.requireNonNull(instant));
        }

        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return Clock.fixed(instant(), zone); }
        @Override public Instant instant() { return value.get(); }
    }

    @Autowired private AdjustableClock clock;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private UserRepository users;
    @Autowired private GroupRepository groups;
    @Autowired private GroupMembershipRepository memberships;
    @Autowired private SeasonRepository seasons;
    @Autowired private GroupSeasonRepository groupSeasons;
    @Autowired private GroupCreatureRepository creatures;
    @Autowired private CreatureSelectionService selection;
    @Autowired private CreatureHatchingNormalizationService hatching;
    @Autowired private SeasonTransitionService transition;

    private final List<Long> userIds = new ArrayList<>();
    private final List<Long> groupIds = new ArrayList<>();
    private final List<Long> groupSeasonIds = new ArrayList<>();
    private final List<Long> speciesIds = new ArrayList<>();
    private Long seasonId;
    private Long speciesA;
    private Long speciesB;

    /**
     * 첫 4주 시즌과 실제 활성 크리처 종 두 개를 독립 Commit으로 준비합니다.
     * 이전 테스트의 데이터가 있으면 다른 사용자의 레코드를 삭제하지 않고 중단합니다.
     * 생성될 그룹 시즌은 각 테스트에서 명시적으로 추가합니다.
     */
    @BeforeEach
    void setUp() {
        clock.set(CREATED);
        assertEquals(0L, count("SELECT COUNT(*) FROM seasons"),
                "반드시 시즌이 없는 독립 test DB에서 실행하세요.");
        assertEquals(0L, count("SELECT COUNT(*) FROM creature_species"),
                "이 테스트는 크리처 종 카탈로그도 비어 있는 test DB가 필요합니다.");

        // 테스트에서 생성한 시즌 하나를 ACTIVE로 확정합니다.
        Season season = Season.schedule(1, START, END, CREATED);
        assertTrue(season.activateAfterInitialization(CREATED));
        seasonId = seasons.saveAndFlush(season).getId();
        speciesA = insertSpecies("G2-SPROUT", "부화테스트-새싹", 1);
        speciesB = insertSpecies("G2-CLOUD", "부화테스트-구름", 2);
    }

    /**
     * 이 테스트에서 생성한 엔티티만 외래키 역순으로 정리합니다.
     * worker가 끝난 뒤 호출되도록 모든 병렬 테스트가 스레드를 종료합니다.
     * 운영 카탈로그나 다른 시즌·그룹의 행은 일절 삭제하지 않습니다.
     */
    @AfterEach
    void tearDown() {
        // 그룹 시즌에 매달린 크리처부터 제거하여 FK 충돌을 방지합니다.
        for (Long id : groupSeasonIds) {
            jdbc.update("DELETE FROM group_creatures WHERE group_season_id = ?", id);
        }
        for (Long id : groupSeasonIds) {
            jdbc.update("DELETE FROM group_seasons WHERE id = ?", id);
        }
        for (Long id : groupIds) {
            jdbc.update("DELETE FROM group_memberships WHERE group_id = ?", id);
            jdbc.update("DELETE FROM game_groups WHERE id = ?", id);
        }
        for (Long id : userIds) {
            jdbc.update("DELETE FROM users WHERE id = ?", id);
        }
        for (Long id : speciesIds) {
            jdbc.update("DELETE FROM creature_species WHERE id = ?", id);
        }
        if (seasonId != null) {
            jdbc.update("DELETE FROM seasons WHERE id = ?", seasonId);
        }
        groupSeasonIds.clear();
        groupIds.clear();
        userIds.clear();
        speciesIds.clear();
        seasonId = null;
    }

    /**
     * 정확한 72시간 이전에는 부화 후보가 없어야 합니다.
     * 실제 단건 부화 API를 직접 호출하더라도 상태가 바뀌지 않아야 합니다.
     * selected_at과 hatched_at 모두 NULL 상태로 남는지 DB에서 확인합니다.
     */
    @Test
    void doesNotHatchBefore72Hours() {
        Fixture f = createEgg(CREATED);
        clock.set(DEADLINE.minusMillis(1));

        assertEquals(0, hatching.normalizeForActiveSeason(seasonId).inspected());
        assertTrue(selection.hatchIfDue(f.groupSeasonId()).isEmpty());
        assertEquals("PREPARING", groupSeasonStatus(f));
        assertEquals("EGG", creatureStage(f));
        assertNull(timestamp("SELECT hatched_at FROM group_creatures WHERE group_season_id = ?", f.groupSeasonId()));
        assertNull(timestamp("SELECT selected_at FROM group_seasons WHERE id = ?", f.groupSeasonId()));
    }

    /**
     * 선택 기한과 정확히 같은 순간에는 미선택 알을 무작위로 부화시킵니다.
     * GroupSeason와 GroupCreature의 종·출처·시각이 일치해야 합니다.
     * 이후 중복 실행에서 부화 시각과 종은 절대 변경되면 안 됩니다.
     */
    @Test
    void hatchesExactlyAtDeadlineAndRepeatedRunIsIdempotent() {
        Fixture f = createEgg(CREATED);
        clock.set(DEADLINE);

        // 정상화가 실제 JPA 트랜잭션에서 부화와 커밋을 수행합니다.
        var first = hatching.normalizeForActiveSeason(seasonId);
        assertEquals(1, first.inspected());
        assertEquals(1, first.hatched());
        assertEquals(0, first.failed());
        assertEquals("ACTIVE", groupSeasonStatus(f));
        assertEquals("HATCHLING", creatureStage(f));
        assertEquals("RANDOM", text("SELECT selection_source FROM group_seasons WHERE id = ?", f.groupSeasonId()));
        assertEquals("RANDOM", text("SELECT selection_source FROM group_creatures WHERE group_season_id = ?", f.groupSeasonId()));
        assertEquals(DEADLINE, timestamp("SELECT selected_at FROM group_seasons WHERE id = ?", f.groupSeasonId()).toInstant());
        assertEquals(DEADLINE, timestamp("SELECT hatched_at FROM group_creatures WHERE group_season_id = ?", f.groupSeasonId()).toInstant());
        Long selected = number("SELECT selected_species_id FROM group_seasons WHERE id = ?", f.groupSeasonId());
        assertTrue(selected.equals(speciesA) || selected.equals(speciesB));
        assertEquals(selected, number("SELECT species_id FROM group_creatures WHERE group_season_id = ?", f.groupSeasonId()));

        // 5분 뒤 재실행해도 종·부화 시각·행 개수를 유지합니다.
        clock.set(DEADLINE.plus(Duration.ofMinutes(5)));
        var repeated = hatching.normalizeForActiveSeason(seasonId);
        assertEquals(0, repeated.inspected());
        assertTrue(selection.hatchIfDue(f.groupSeasonId()).isEmpty());
        assertEquals(DEADLINE, timestamp("SELECT hatched_at FROM group_creatures WHERE group_season_id = ?", f.groupSeasonId()).toInstant());
        assertEquals(selected, number("SELECT species_id FROM group_creatures WHERE group_season_id = ?", f.groupSeasonId()));
        assertEquals(1L, count("SELECT COUNT(*) FROM group_creatures WHERE group_season_id = ?", f.groupSeasonId()));
    }

    /**
     * 관리자가 선택 기한 전에 지정한 종을 실제 선택 API로 확정합니다.
     * 자동 부화는 기존 종과 최초 selected_at을 보존하고 무작위 선택하지 않습니다.
     * GROUP_SEASON과 GROUP_CREATURE 두 테이블을 DB 값으로 비교합니다.
     */
    @Test
    void preservesAdminSelectionAndOriginalSelectedAt() {
        Fixture f = createEgg(CREATED);
        Instant chosenAt = DEADLINE.minus(Duration.ofMinutes(10));
        clock.set(chosenAt);

        // 실제 관리자 권한과 그룹 가입 기록을 이용해 종을 선택합니다.
        var chosen = selection.selectSpecies(f.ownerId(), speciesB);
        assertEquals(speciesB, chosen.speciesId());
        assertEquals("ADMIN", chosen.selectionSource());
        assertEquals(chosenAt, chosen.selectedAt());
        clock.set(DEADLINE.plusSeconds(1));
        assertEquals(1, hatching.normalizeForActiveSeason(seasonId).hatched());

        assertEquals("ADMIN", text("SELECT selection_source FROM group_seasons WHERE id = ?", f.groupSeasonId()));
        assertEquals("ADMIN", text("SELECT selection_source FROM group_creatures WHERE group_season_id = ?", f.groupSeasonId()));
        assertEquals(speciesB, number("SELECT selected_species_id FROM group_seasons WHERE id = ?", f.groupSeasonId()));
        assertEquals(speciesB, number("SELECT species_id FROM group_creatures WHERE group_season_id = ?", f.groupSeasonId()));
        assertEquals(chosenAt, timestamp("SELECT selected_at FROM group_seasons WHERE id = ?", f.groupSeasonId()).toInstant());
        assertEquals(DEADLINE.plusSeconds(1), timestamp("SELECT hatched_at FROM group_creatures WHERE group_season_id = ?", f.groupSeasonId()).toInstant());
    }

    /**
     * 알 상태에서 미리 적립한 경험치는 부화 시 한 번에 성장 단계로 반영됩니다.
     * 유년·성장·성숙·완전체 경계를 실제 DB Flush와 Dirty Checking으로 확인합니다.
     * 부화 전까지는 경험치가 높아도 EGG 상태를 유지해야 합니다.
     */
    @Test
    void accumulatedEggExperienceDeterminesStageOnHatching() {
        Fixture growing = createEgg(CREATED);
        Fixture mature = createEgg(CREATED);
        Fixture complete = createEgg(CREATED);

        // 테스트용 기록을 정상 엔티티 메서드로 누적해 각 임계값에 도달시킵니다.
        addEggExperience(growing, GroupCreature.GROWING_THRESHOLD);
        addEggExperience(mature, GroupCreature.MATURE_THRESHOLD);
        addEggExperience(complete, GroupCreature.COMPLETE_THRESHOLD);
        assertEquals("EGG", creatureStage(growing));
        assertEquals("EGG", creatureStage(mature));
        assertEquals("EGG", creatureStage(complete));

        clock.set(DEADLINE);
        var result = hatching.normalizeForActiveSeason(seasonId);
        assertEquals(3, result.hatched());
        assertEquals(0, result.failed());
        assertStageAndExperience(growing, "GROWING", GroupCreature.GROWING_THRESHOLD);
        assertStageAndExperience(mature, "MATURE", GroupCreature.MATURE_THRESHOLD);
        assertStageAndExperience(complete, "COMPLETE", GroupCreature.COMPLETE_THRESHOLD);
    }

    /**
     * 같은 알을 서로 다른 두 DB 트랜잭션에서 동시에 부화시킵니다.
     * 그룹 시즌 배타 잠금 후 재검사로 실제 성공 건수는 정확히 하나여야 합니다.
     * 최종 종·경험치·부화 시각과 크리처 행은 하나만 유지되어야 합니다.
     */
    @Test
    @Timeout(30)
    void simultaneousHatchCallsCommitExactlyOnce() throws Exception {
        Fixture f = createEgg(CREATED);
        clock.set(DEADLINE);
        ExecutorService workers = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch go = new CountDownLatch(1);

        try {
            Future<Optional<CreatureSelectionResponse>> first = workers.submit(() -> hatchWhenReleased(f, ready, go));
            Future<Optional<CreatureSelectionResponse>> second = workers.submit(() -> hatchWhenReleased(f, ready, go));
            assertTrue(ready.await(10, TimeUnit.SECONDS), "두 스레드가 준비되지 않았습니다.");
            go.countDown();

            // Future.get()은 스레드 내부 예외를 테스트 실패로 노출합니다.
            int successCount = (first.get(15, TimeUnit.SECONDS).isPresent() ? 1 : 0)
                    + (second.get(15, TimeUnit.SECONDS).isPresent() ? 1 : 0);
            assertEquals(1, successCount);
            assertEquals("ACTIVE", groupSeasonStatus(f));
            assertEquals(1L, count("SELECT COUNT(*) FROM group_creatures WHERE group_season_id = ?", f.groupSeasonId()));
            assertEquals(DEADLINE, timestamp("SELECT hatched_at FROM group_creatures WHERE group_season_id = ?", f.groupSeasonId()).toInstant());
            assertEquals(0, hatching.normalizeForActiveSeason(seasonId).inspected());
        } finally {
            go.countDown();
            workers.shutdownNow();
            assertTrue(workers.awaitTermination(10, TimeUnit.SECONDS), "부화 worker가 종료되지 않았습니다.");
        }
    }

    /**
     * 한 그룹의 필수 크리처 행이 누락돼 부화에 실패해도 다음 그룹을 진행합니다.
     * 기존 그룹의 알과 기한은 유지한 채 빠진 자식 행을 복구합니다.
     * 다음 정기 점검에서 오직 실패 그룹만 다시 부화하는지 확인합니다.
     */
    @Test
    void failedGroupDoesNotBlockFollowingGroupAndCanRetry() {
        Fixture broken = createEgg(CREATED);
        Fixture healthy = createEgg(CREATED);
        jdbc.update("DELETE FROM group_creatures WHERE group_season_id = ?", broken.groupSeasonId());
        clock.set(DEADLINE);

        var first = hatching.normalizeForActiveSeason(seasonId);
        assertEquals(2, first.inspected());
        assertEquals(1, first.hatched());
        assertEquals(1, first.failed());
        assertEquals("PREPARING", groupSeasonStatus(broken));
        assertEquals("ACTIVE", groupSeasonStatus(healthy));
        assertNull(timestamp("SELECT selected_at FROM group_seasons WHERE id = ?", broken.groupSeasonId()));

        // 기존 GroupSeason의 생성 시각과 선택 기한을 건드리지 않고 누락 알만 복원합니다.
        GroupSeason original = groupSeasons.findById(broken.groupSeasonId()).orElseThrow();
        creatures.saveAndFlush(GroupCreature.egg(original, CREATED));
        clock.set(DEADLINE.plus(Duration.ofMinutes(5)));
        var recovered = hatching.normalizeForActiveSeason(seasonId);
        assertEquals(1, recovered.inspected());
        assertEquals(1, recovered.hatched());
        assertEquals(0, recovered.failed());
        assertEquals("ACTIVE", groupSeasonStatus(broken));
        assertEquals(1L, count("SELECT COUNT(*) FROM group_creatures WHERE group_season_id = ?", broken.groupSeasonId()));
    }

    /**
     * 활성 종 카탈로그가 비어 있으면 무작위 부화가 실패하고 전체 변경이 롤백됩니다.
     * 종이 다시 활성화되면 같은 그룹 시즌을 다음 점검에서 정상 복구합니다.
     * 이전 실패가 가짜 selected_at이나 ACTIVE 상태를 남기지 않아야 합니다.
     */
    @Test
    void missingActiveSpeciesRollsBackAndLaterRecovers() {
        Fixture f = createEgg(CREATED);
        jdbc.update("UPDATE creature_species SET status = 'INACTIVE' WHERE id IN (?, ?)", speciesA, speciesB);
        clock.set(DEADLINE);

        var failed = hatching.normalizeForActiveSeason(seasonId);
        assertEquals(1, failed.inspected());
        assertEquals(0, failed.hatched());
        assertEquals(1, failed.failed());
        assertEquals("PREPARING", groupSeasonStatus(f));
        assertEquals("EGG", creatureStage(f));
        assertNull(timestamp("SELECT selected_at FROM group_seasons WHERE id = ?", f.groupSeasonId()));

        // 외부 종 카탈로그가 정상화되면 기존 알로 부화를 다시 시도합니다.
        jdbc.update("UPDATE creature_species SET status = 'ACTIVE' WHERE id = ?", speciesA);
        clock.set(DEADLINE.plus(Duration.ofMinutes(5)));
        var recovered = hatching.normalizeForActiveSeason(seasonId);
        assertEquals(1, recovered.hatched());
        assertEquals(0, recovered.failed());
        assertEquals(speciesA, number("SELECT species_id FROM group_creatures WHERE group_season_id = ?", f.groupSeasonId()));
    }

    /**
     * 서버가 꺼진 사이 부화 기한을 넘고 시즌까지 종료되었다면 소급 부화하지 않습니다.
     * 시즌 전이 Service로 CLOSING 확정 후 다시 부화를 시도해도 알을 보존합니다.
     * 선택 기한은 종료 이전이어도 과거 부화 시각을 만들어내지 않습니다.
     */
    @Test
    void expiredSeasonNeverBackfillsHatching() {
        Fixture f = createEgg(CREATED);
        clock.set(END.plus(Duration.ofMinutes(1)));

        // 부화하지 못한 상태로 시즌이 먼저 종료된 상황을 재현합니다.
        assertTrue(transition.beginClosingIfExpired(seasonId));
        assertEquals("CLOSING", text("SELECT status FROM seasons WHERE id = ?", seasonId));
        assertEquals(0, hatching.normalizeForActiveSeason(seasonId).inspected());
        assertTrue(selection.hatchIfDue(f.groupSeasonId()).isEmpty());
        assertEquals("PREPARING", groupSeasonStatus(f));
        assertEquals("EGG", creatureStage(f));
        assertNull(timestamp("SELECT hatched_at FROM group_creatures WHERE group_season_id = ?", f.groupSeasonId()));
    }

    /**
     * 시즌 종료보다 늦게 도달하는 72시간 기한은 부화 대상이 아닙니다.
     * 종료 직전에도 알로 남으며 종료 뒤에는 소급 처리할 수 없어야 합니다.
     * 이 경우 후보 쿼리와 실제 서비스의 기한 검사 양쪽을 확인합니다.
     */
    @Test
    void deadlineAfterSeasonEndMustRemainEgg() {
        Instant lateCreation = END.minus(Duration.ofDays(2));
        Fixture f = createEgg(lateCreation);
        clock.set(END.minusMillis(1));

        assertEquals(0, hatching.normalizeForActiveSeason(seasonId).inspected());
        assertTrue(selection.hatchIfDue(f.groupSeasonId()).isEmpty());
        clock.set(END.plusSeconds(1));
        assertEquals(0, hatching.normalizeForActiveSeason(seasonId).inspected());
        assertEquals("EGG", creatureStage(f));
        assertNull(timestamp("SELECT hatched_at FROM group_creatures WHERE group_season_id = ?", f.groupSeasonId()));
        assertEquals(lateCreation.plus(Duration.ofHours(72)), f.deadline());
    }

    /**
     * 진짜 User·Group·PRIMARY 멤버십·GroupSeason·GroupCreature를 생성합니다.
     * 그룹 생성 시각을 바꿔 부화 기한이 시즌 끝보다 늦은 사례도 시험합니다.
     * 모든 행은 독립된 저장소 호출의 Commit이 끝난 뒤 업무 테스트로 전달됩니다.
     */
    private Fixture createEgg(Instant createdAt) {
        User owner = users.saveAndFlush(User.register("USER", createdAt));
        userIds.add(owner.getId());
        Group group = groups.saveAndFlush(Group.create(
                "부화검증-" + owner.getId(), "g2-hatch-" + owner.getId(), "", owner, createdAt));
        groupIds.add(group.getId());
        memberships.saveAndFlush(GroupMembership.primary(group, owner, createdAt));

        // 기존 GroupSeason 도메인의 정확한 72시간 기한 팩토리를 재사용합니다.
        Season season = seasons.findById(seasonId).orElseThrow();
        GroupSeason gs = groupSeasons.saveAndFlush(GroupSeason.start(group, season, createdAt));
        groupSeasonIds.add(gs.getId());
        creatures.saveAndFlush(GroupCreature.egg(gs, createdAt));
        return new Fixture(owner.getId(), group.getId(), gs.getId(), gs.getSelectionDeadlineAt());
    }

    /**
     * 테스트 카탈로그의 종을 MySQL 실제 UNIQUE 코드로 삽입합니다.
     * 자동 부화가 임의 종을 선택해도 두 코드 이외의 값은 허용하지 않습니다.
     * 테스트 이후 생성된 PK만 지울 수 있도록 ID를 별도로 기록합니다.
     */
    private Long insertSpecies(String code, String name, int order) {
        jdbc.update("""
                INSERT INTO creature_species (
                    code
                  , name
                  , asset_key
                  , status
                  , display_order
                  , created_at
                  , updated_at
                ) VALUES (?, ?, ?, 'ACTIVE', ?, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
                """, code, name, "creatures/" + code, order);
        Long id = number("SELECT id FROM creature_species WHERE code = ?", code);
        speciesIds.add(id);
        return id;
    }

    /**
     * 실제 크리처 엔티티의 누적 메서드를 이용해 알 경험치를 준비합니다.
     * 경험치가 임계값에 도달해도 부화 전에 EGG로 남는지 별도로 검사합니다.
     * 독립 Commit 이후 부화 서비스가 읽는 DB 값을 기준으로 테스트합니다.
     */
    private void addEggExperience(Fixture f, long amount) {
        GroupCreature creature = creatures.findSnapshotByGroupSeasonId(f.groupSeasonId()).orElseThrow();
        creature.addExperience(amount, CREATED.plus(Duration.ofHours(1)));
        creatures.saveAndFlush(creature);
    }

    /**
     * 두 worker가 동일 시점에 Spring의 실제 부화 트랜잭션에 진입하게 합니다.
     * 동시성 제어는 CountDownLatch가 아닌 시즌·그룹 시즌의 DB 잠금이 책임집니다.
     * 테스트의 두 Future는 서로 다른 MySQL 커넥션에서 수행됩니다.
     */
    private Optional<CreatureSelectionResponse> hatchWhenReleased(
            Fixture f, CountDownLatch ready, CountDownLatch go
    ) throws InterruptedException {
        ready.countDown();
        if (!go.await(10, TimeUnit.SECONDS)) {
            throw new IllegalStateException("동시 부화 시작 신호를 받지 못했습니다.");
        }
        return selection.hatchIfDue(f.groupSeasonId());
    }

    /**
     * 테이블에 저장된 상태 문자열을 사용해 DB Commit 결과를 검사합니다.
     * EntityManager의 1차 캐시 영향을 피하기 위해 JdbcTemplate으로 재조회합니다.
     * 매 테스트가 실제 MySQL 저장 결과를 평가하도록 합니다.
     */
    private String groupSeasonStatus(Fixture f) {
        return text("SELECT status FROM group_seasons WHERE id = ?", f.groupSeasonId());
    }

    /**
     * 부화와 단계 성장 후 실제 저장된 크리처 단계를 반환합니다.
     * 그룹 시즌 ID는 크리처 UNIQUE 키이므로 한 행만 조회되어야 합니다.
     * 누락 또는 중복 상태는 Spring JDBC 조회 예외로 테스트를 실패시킵니다.
     */
    private String creatureStage(Fixture f) {
        return text("SELECT growth_stage FROM group_creatures WHERE group_season_id = ?", f.groupSeasonId());
    }

    /**
     * 기대한 성장 단계와 누적 경험치가 DB에 함께 보존됐는지 확인합니다.
     * 엔티티 메서드를 통한 실제 성장 결과가 Commit된 값과 같아야 합니다.
     * 누락된 상태 전이나 경험치 유실을 직접적으로 검증합니다.
     */
    private void assertStageAndExperience(Fixture f, String stage, long experience) {
        assertEquals(stage, creatureStage(f));
        assertEquals(experience, count("SELECT total_experience FROM group_creatures WHERE group_season_id = ?", f.groupSeasonId()));
        assertEquals(DEADLINE, timestamp("SELECT growth_stage_reached_at FROM group_creatures WHERE group_season_id = ?", f.groupSeasonId()).toInstant());
    }

    /**
     * 단일 숫자 컬럼을 Long으로 읽어 FK와 식별자를 검사합니다.
     * 컬럼이 없으면 NULL을 유지해 명확한 assertion 오류를 표시할 수 있습니다.
     * count는 다른 메서드에서 같은 형태로 재사용합니다.
     */
    private Long number(String sql, Object... args) {
        return jdbc.queryForObject(sql, Long.class, args);
    }

    /**
     * 실제 데이터베이스의 COUNT 또는 정수 점수 컬럼을 읽습니다.
     * DB의 NULL은 여기서 허용하지 않아 누락된 값을 조기에 발견합니다.
     * JDBC의 수형 변환과 SQL 집계 결과를 함께 검증합니다.
     */
    private long count(String sql, Object... args) {
        return Objects.requireNonNull(number(sql, args));
    }

    /**
     * 상태나 출처를 문자열로 읽어 Repository 캐시 없이 검증합니다.
     * 조회 결과는 명시적인 기대값과 비교하므로 NULL을 허용하지 않습니다.
     * 테스트 실패 시 실제 테이블의 해당 속성을 쉽게 추적할 수 있습니다.
     */
    private String text(String sql, Object... args) {
        return Objects.requireNonNull(jdbc.queryForObject(sql, String.class, args));
    }

    /**
     * DATETIME(6)의 UTC 시각을 JDBC Timestamp로 가져옵니다.
     * 아직 선택하거나 부화하지 않은 레코드는 null 그대로 반환합니다.
     * 부화 실제 시각과 selected_at 보존 여부를 테스트합니다.
     */
    private Timestamp timestamp(String sql, Object... args) {
        return jdbc.queryForObject(sql, Timestamp.class, args);
    }

    /**
     * 테스트에서 만든 한 그룹의 식별자와 부화 기한을 보존합니다.
     * 실제 그룹의 주 관리자 ID로 종 선택 API까지 호출할 수 있습니다.
     * JPA 지연 로딩과 별개로 테스트 데이터의 정합성을 검증합니다.
     */
    private record Fixture(Long ownerId, Long groupId, Long groupSeasonId, Instant deadline) {
    }
}
