package com.team.sharedcreature.ranking.integration;

import com.team.sharedcreature.season.domain.SeasonStatus;

import com.team.sharedcreature.auth.domain.GlobalRole;

import com.team.sharedcreature.auth.domain.User;
import com.team.sharedcreature.auth.domain.UserProfile;
import com.team.sharedcreature.group.domain.Group;
import com.team.sharedcreature.group.domain.GroupMembership;
import com.team.sharedcreature.group.domain.GroupMembershipStatus;
import com.team.sharedcreature.group.domain.GroupRole;
import com.team.sharedcreature.group.domain.GroupInviteLink;
import com.team.sharedcreature.group.domain.GroupInviteLinkStatus;
import com.team.sharedcreature.group.domain.GroupAdminChange;
import com.team.sharedcreature.group.domain.GroupAdminChangeType;
import com.team.sharedcreature.group.domain.ModerationAction;
import com.team.sharedcreature.group.domain.ModerationActionType;
import com.team.sharedcreature.group.domain.UserReport;
import com.team.sharedcreature.group.domain.UserReportStatus;
import com.team.sharedcreature.group.domain.UserReportCategory;
import com.team.sharedcreature.group.infrastructure.UserReportRepository;
import com.team.sharedcreature.admin.application.AdminReportQueryService;
import com.team.sharedcreature.admin.infrastructure.AdminAnalyticsRepository;
import com.team.sharedcreature.group.infrastructure.GroupInviteLinkRepository;
import com.team.sharedcreature.group.infrastructure.GroupMembershipRepository;
import com.team.sharedcreature.ranking.application.RankingService;
import com.team.sharedcreature.season.domain.*;
import com.team.sharedcreature.season.infrastructure.CreatureSpeciesRepository;
import com.team.sharedcreature.season.infrastructure.SeasonRepository;
import com.team.sharedcreature.season.infrastructure.GroupSeasonRepository;
import com.team.sharedcreature.season.api.SeasonResponse;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.NullSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContextInitializer;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.data.domain.PageRequest;
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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
    @Autowired private GroupMembershipRepository memberships;
    @Autowired private GroupInviteLinkRepository inviteLinks;
    @Autowired private UserReportRepository reports;
    @Autowired private AdminReportQueryService reportQueries;
    @Autowired private AdminAnalyticsRepository analytics;
    @Autowired private CreatureSpeciesRepository speciesRepository;
    @Autowired private SeasonRepository seasons;
    @Autowired private GroupSeasonRepository groupSeasons;
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

    /** 미선택·관리자 선택·무작위 선택의 출처가 두 기록에 동일하게 저장되는지 검증합니다. */
    @ParameterizedTest
    @EnumSource(CreatureSelectionSource.class)
    @NullSource
    void 종_선택_출처는_미선택_null과_부화_후_DB_코드를_유지한다(CreatureSelectionSource source) {
        GroupCreature creature = GroupCreature.egg(groupSeason, NOW);
        if (source != null) {
            CreatureSpecies species = org.springframework.beans.BeanUtils.instantiateClass(CreatureSpecies.class);
            ReflectionTestUtils.setField(species, "code", "source-" + UUID.randomUUID());
            ReflectionTestUtils.setField(species, "name", "선택 출처 검증");
            ReflectionTestUtils.setField(species, "assetKey", "species/test-source");
            ReflectionTestUtils.setField(species, "status", CreatureSpeciesStatus.ACTIVE);
            ReflectionTestUtils.setField(species, "createdAt", NOW);
            ReflectionTestUtils.setField(species, "updatedAt", NOW);
            em.persist(species);
            if (source == CreatureSelectionSource.ADMIN) {
                groupSeason.selectSpecies(species, owner, NOW);
            } else {
                groupSeason.selectRandomSpecies(species, NOW);
            }
            creature.hatch(species, groupSeason.getSelectionSource(), NOW.plusSeconds(1));
            groupSeason.activate(NOW.plusSeconds(1));
        }
        em.persist(creature);
        em.flush();
        Long groupSeasonId = groupSeason.getId();
        Long creatureId = creature.getId();
        em.clear();

        assertThat(em.find(GroupSeason.class, groupSeasonId).getSelectionSource()).isEqualTo(source);
        GroupCreature reloaded = em.find(GroupCreature.class, creatureId);
        assertThat(reloaded.getSelectionSource()).isEqualTo(source);
        assertThat(reloaded.getHatchedAt()).isEqualTo(source == null ? null : NOW.plusSeconds(1));
        Object[] stored = (Object[]) em.createNativeQuery("""
                SELECT gs.selection_source, creature.selection_source
                  FROM group_seasons gs JOIN group_creatures creature ON creature.group_season_id = gs.id
                 WHERE gs.id = :id
                """).setParameter("id", groupSeasonId).getSingleResult();
        String expected = source == null ? null : source.name();
        assertThat(stored).containsExactly(expected, expected);
    }

    /** 그룹 참여 상태의 DB 매핑과 진행 중 조회·부화 대상 선정을 검증합니다. */
    @ParameterizedTest
    @EnumSource(GroupSeasonStatus.class)
    void 그룹_시즌_상태는_DB_코드와_조회_조건을_유지한다(GroupSeasonStatus status) {
        // 조회 조건을 분리해 검증하기 위해 각 상태와 경과한 선택 기한을 준비합니다.
        ReflectionTestUtils.setField(groupSeason, "status", status);
        ReflectionTestUtils.setField(groupSeason, "selectionDeadlineAt", NOW.minusSeconds(1));
        em.flush();
        Long groupSeasonId = groupSeason.getId();
        Long groupId = group.getId();
        Long seasonId = season.getId();
        em.clear();

        GroupSeason reloaded = groupSeasons.findById(groupSeasonId).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(status);
        assertThat(em.createNativeQuery("SELECT status FROM group_seasons WHERE id = :id", String.class)
                .setParameter("id", groupSeasonId).getSingleResult()).isEqualTo(status.name());
        if (status == GroupSeasonStatus.ENDED) {
            assertThat(groupSeasons.findOpenByGroupIdForUpdate(groupId, NOW)).isEmpty();
        } else {
            assertThat(groupSeasons.findOpenByGroupIdForUpdate(groupId, NOW)).contains(reloaded);
        }
        var dueIds = groupSeasons.findDueForHatching(seasonId, NOW, 0L, PageRequest.of(0, 100));
        if (status == GroupSeasonStatus.PREPARING) {
            assertThat(dueIds).contains(groupSeasonId);
        } else {
            assertThat(dueIds).doesNotContain(groupSeasonId);
        }
    }

    /** 시즌 상태·종료 출처의 저장 코드와 조회·복구 쿼리의 호환성을 검증합니다. */
    @ParameterizedTest
    @CsvSource({
            "SCHEDULED,",
            "ACTIVE,",
            "CLOSING,ACTIVE",
            "CLOSING,SCHEDULED",
            "ENDED,ACTIVE",
            "ENDED,SCHEDULED"
    })
    void 시즌_상태와_종료_출처는_기존_DB_코드와_조회_계약을_유지한다(
            SeasonStatus status, SeasonStatus origin
    ) {
        ReflectionTestUtils.setField(season, "status", status);
        ReflectionTestUtils.setField(season, "closingFromStatus", origin);
        // 종료 복구 대상 조회도 확인할 수 있도록 만료된 시즌으로 준비합니다.
        ReflectionTestUtils.setField(season, "endAt", NOW);
        em.flush();
        Long seasonId = season.getId();
        em.clear();

        Season reloaded = seasons.findById(seasonId).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(status);
        assertThat(reloaded.getClosingFromStatus()).isEqualTo(origin);
        Object[] stored = (Object[]) em.createNativeQuery(
                        "SELECT status, closing_from_status FROM seasons WHERE id = :id")
                .setParameter("id", seasonId).getSingleResult();
        assertThat(stored[0]).isEqualTo(status.name());
        assertThat(stored[1]).isEqualTo(origin == null ? null : origin.name());
        assertThat(SeasonResponse.from(reloaded).status()).isEqualTo(status.name());
        assertThat(seasons.findFirstByStatusOrderByStartAtDesc(status).orElseThrow().getStatus())
                .isEqualTo(status);
        assertThat(seasons.findForShareByStatus(status, PageRequest.of(0, 1)))
                .singleElement().satisfies(row -> assertThat(row.getStatus()).isEqualTo(status));
        assertThat(seasons.findIdsNeedingCloseNormalization(NOW)).contains(seasonId);
        if (status == SeasonStatus.CLOSING) {
            assertThat(reloaded.requiresRankingSnapshotOnClosing()).isEqualTo(origin == SeasonStatus.ACTIVE);
        }
    }

    /** 기존 문자열로 저장된 종의 상태 매핑과 활성 조회 제외 규칙을 검증합니다. */
    @ParameterizedTest
    @EnumSource(CreatureSpeciesStatus.class)
    void 크리처_종은_기존_DB_상태와_조회_조건을_유지한다(CreatureSpeciesStatus status) {
        String code = "enum-" + UUID.randomUUID();
        em.flush();
        // 운영 DB에 저장된 문자열과 같은 방식으로 기존 데이터를 준비합니다.
        em.createNativeQuery("""
                INSERT INTO creature_species
                    (code, name, asset_key, status, display_order, created_at, updated_at)
                VALUES (:code, :name, :asset, :status, 1, :now, :now)
                """)
                .setParameter("code", code)
                .setParameter("name", "상태 호환성 검증")
                .setParameter("asset", "species/test-enum")
                .setParameter("status", status.name())
                .setParameter("now", NOW)
                .executeUpdate();
        em.clear();

        CreatureSpecies species = em.createQuery(
                        "SELECT species FROM CreatureSpecies species WHERE species.code = :code", CreatureSpecies.class)
                .setParameter("code", code).getSingleResult();
        assertThat(species.getStatus()).isEqualTo(status);
        assertThat(speciesRepository.findByIdAndStatus(species.getId(), status)).contains(species);
        assertThat(speciesRepository.findAllByStatusOrderByDisplayOrderAscIdAsc(status))
                .extracting(CreatureSpecies::getId).contains(species.getId());
        if (status == CreatureSpeciesStatus.INACTIVE) {
            assertThat(speciesRepository.findByIdAndStatus(species.getId(), CreatureSpeciesStatus.ACTIVE)).isEmpty();
            assertThat(speciesRepository.findAllByStatusOrderByDisplayOrderAscIdAsc(CreatureSpeciesStatus.ACTIVE))
                    .extracting(CreatureSpecies::getId).doesNotContain(species.getId());
        }
    }

    /** 모든 신고 분류가 기존 DB 코드와 일반·관리자 응답을 유지하는지 검증합니다. */
    @ParameterizedTest
    @EnumSource(UserReportCategory.class)
    void 신고_분류는_DB와_API의_문자열_계약을_유지한다(UserReportCategory category) {
        UserReport report = UserReport.receive(owner, user(), group, category, "신고 분류 검증", NOW);
        em.persist(report);
        em.flush();
        Long reportId = report.getId();
        em.clear();

        // 영속성 컨텍스트를 비운 뒤 기존 저장 코드와 두 응답 경로를 확인합니다.
        UserReport reloaded = reports.findById(reportId).orElseThrow();
        assertThat(reloaded.getCategory()).isEqualTo(category);
        assertThat(em.createNativeQuery("SELECT category FROM user_reports WHERE id = :id", String.class)
                .setParameter("id", reportId).getSingleResult()).isEqualTo(category.name());
        assertThat(com.team.sharedcreature.group.application.UserReportService.toResponse(reloaded).category())
                .isEqualTo(category.name());
        assertThat(reportQueries.get(reportId).category()).isEqualTo(category.name());
    }

    /** 신고 상태 전환·필터·집계와 API 문자열의 호환성을 실제 DB에서 검증합니다. */
    @ParameterizedTest
    @EnumSource(UserReportStatus.class)
    void 신고_상태는_DB_조회와_API_문자열을_유지한다(UserReportStatus status) {
        UserReport report = UserReport.receive(owner, user(), group, UserReportCategory.OTHER, "신고 상태 검증", NOW);
        if (status == UserReportStatus.REJECTED) {
            report.reject(owner, "기각 사유", NOW);
        } else if (status == UserReportStatus.RESOLVED) {
            report.resolve(owner, "조치 완료 사유", NOW);
        }
        em.persist(report);
        em.flush();
        Long reportId = report.getId();
        Long groupId = group.getId();
        em.clear();

        UserReport reloaded = reports.findById(reportId).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(status);
        assertThat(em.createNativeQuery("SELECT status FROM user_reports WHERE id = :id", String.class)
                .setParameter("id", reportId).getSingleResult()).isEqualTo(status.name());
        assertThat(reports.findByStatusOrderByCreatedAtDescIdDesc(status, PageRequest.of(0, 50)).getContent())
                .extracting(UserReport::getId).contains(reportId);
        assertThat(reports.countByStatus(status)).isPositive();
        assertThat(reportQueries.get(reportId).status()).isEqualTo(status.name());
        assertThat(reportQueries.list(status.name(), 0, 50).getContent())
                .allSatisfy(row -> assertThat(row.status()).isEqualTo(status.name()));
        assertThat(analytics.countPendingReports(groupId))
                .isEqualTo(status == UserReportStatus.RECEIVED ? 1L : 0L);

        // 심사가 끝난 신고의 결과는 다시 변경할 수 없습니다.
        if (status != UserReportStatus.RECEIVED) {
            assertThatThrownBy(() -> reloaded.reject(owner, "재기각", NOW))
                    .isInstanceOf(IllegalStateException.class);
            assertThatThrownBy(() -> reloaded.resolve(owner, "재처리", NOW))
                    .isInstanceOf(IllegalStateException.class);
            assertThat(reloaded.getStatus()).isEqualTo(status);
        }
    }

    /** 강퇴 조치 유형의 저장 코드와 신고 연결 여부를 실제 DB에서 검증합니다. */
    @ParameterizedTest
    @EnumSource(ModerationActionType.class)
    void 강퇴_조치_유형은_DB_문자열과_신고_연결을_유지한다(ModerationActionType type) {
        User target = user();
        GroupMembership membership = GroupMembership.member(group, target, NOW);
        em.persist(membership);
        UserReport report = null;
        ModerationAction action;
        if (type == ModerationActionType.REMOVE_REPORTED) {
            report = UserReport.receive(owner, target, group, UserReportCategory.OTHER, "신고 검증", NOW);
            em.persist(report);
            action = ModerationAction.reportedRemoval(report, membership, target, owner, "신고 심사", NOW);
        } else {
            action = ModerationAction.inactiveRemoval(group, membership, target, owner, "비활동 강퇴", NOW);
        }
        em.persist(action);
        em.flush();
        Long actionId = action.getId();
        Long reportId = report == null ? null : report.getId();
        em.clear();

        // 저장 문자열과 JPA 조회 결과를 함께 확인합니다.
        ModerationAction reloaded = em.find(ModerationAction.class, actionId);
        assertThat(reloaded.getActionType()).isEqualTo(type);
        assertThat(reloaded.getReport() == null ? null : reloaded.getReport().getId()).isEqualTo(reportId);
        assertThat(em.createNativeQuery("SELECT action_type FROM moderation_actions WHERE id = :id", String.class)
                .setParameter("id", actionId).getSingleResult()).isEqualTo(type.name());
    }

    /** 기존 문자열로 저장된 관리자 이력이 Enum 전환 후에도 조회되는지 검증합니다. */
    @ParameterizedTest
    @CsvSource({
            "INITIAL_PRIMARY,,PRIMARY",
            "TRANSFER_PRIMARY,DEPUTY,PRIMARY",
            "GRANT_DEPUTY,MEMBER,DEPUTY",
            "REVOKE_DEPUTY,DEPUTY,MEMBER"
    })
    void 기존_관리자_변경이력의_문자열과_null_역할을_Enum으로_조회한다(
            String changeType, String previousRole, String nextRole
    ) {
        em.flush();
        // 마이그레이션 이전과 동일한 문자열을 SQL로 저장합니다.
        em.createNativeQuery("""
                INSERT INTO group_admin_changes
                    (group_id, target_user_id, actor_user_id, change_type, previous_role, new_role, created_at)
                VALUES (:groupId, :userId, :userId, :changeType, :previousRole, :nextRole, :now)
                """)
                .setParameter("groupId", group.getId())
                .setParameter("userId", owner.getId())
                .setParameter("changeType", changeType)
                .setParameter("previousRole", previousRole)
                .setParameter("nextRole", nextRole)
                .setParameter("now", NOW)
                .executeUpdate();
        Long groupId = group.getId();
        em.clear();

        GroupAdminChange history = em.createQuery(
                        "SELECT history FROM GroupAdminChange history WHERE history.group.id = :groupId",
                        GroupAdminChange.class)
                .setParameter("groupId", groupId).getSingleResult();
        assertThat(history.getChangeType()).isEqualTo(GroupAdminChangeType.valueOf(changeType));
        assertThat(history.getPreviousRole())
                .isEqualTo(previousRole == null ? null : GroupRole.valueOf(previousRole));
        assertThat(history.getNewRole()).isEqualTo(GroupRole.valueOf(nextRole));
        assertThat(history.getCreatedAt()).isEqualTo(NOW);
    }

    /** 초대 상태의 DB 저장값과 활성 링크 조회·잠금 쿼리를 함께 검증합니다. */
    @Test
    void 무효화된_초대는_활성_조회에서_제외하고_새_링크만_반환한다() {
        String tokenHash = UUID.randomUUID().toString().replace("-", "").repeat(2);
        GroupInviteLink previous = GroupInviteLink.issue(group, tokenHash, owner, NOW);
        em.persist(previous);
        em.flush();
        Long previousId = previous.getId();
        Long groupId = group.getId();
        em.clear();

        // 두 잠금 쿼리가 기존 활성 링크를 동일하게 선택해야 합니다.
        GroupInviteLink locked = inviteLinks.findActiveByGroupIdForUpdate(groupId).orElseThrow();
        assertThat(locked.getId()).isEqualTo(previousId);
        assertThat(inviteLinks.findActiveForUpdate(previousId, groupId)).contains(locked);
        locked.invalidate(NOW);
        em.flush();

        // 재발급 전에 기존 활성 링크의 유일 키를 해제하는 순서를 유지합니다.
        String nextHash = UUID.randomUUID().toString().replace("-", "").repeat(2);
        GroupInviteLink replacement = GroupInviteLink.issue(
                em.getReference(Group.class, groupId), nextHash,
                em.getReference(User.class, owner.getId()), NOW.plusSeconds(1)
        );
        em.persist(replacement);
        em.flush();
        Long replacementId = replacement.getId();
        em.clear();

        assertThat(inviteLinks.findByIdAndStatus(previousId, GroupInviteLinkStatus.ACTIVE)).isEmpty();
        assertThat(inviteLinks.findActiveForUpdate(previousId, groupId)).isEmpty();
        assertThat(inviteLinks.findByGroup_IdAndStatus(groupId, GroupInviteLinkStatus.ACTIVE)
                .orElseThrow().getId()).isEqualTo(replacementId);
        assertThat(inviteLinks.findActiveByGroupIdForUpdate(groupId).orElseThrow().getId())
                .isEqualTo(replacementId);
        assertThat(inviteLinks.findByIdAndStatus(previousId, GroupInviteLinkStatus.INVALIDATED)
                .orElseThrow().getInvalidatedAt()).isEqualTo(NOW);
        assertThat(em.createNativeQuery("SELECT status FROM group_invite_links WHERE id = :id", String.class)
                .setParameter("id", previousId).getSingleResult()).isEqualTo("INVALIDATED");
    }

    /** 역할 Enum의 DB 저장값과 구성원 목록 JPQL의 문자열 응답을 함께 검증합니다. */
    @ParameterizedTest
    @EnumSource(GroupRole.class)
    void 그룹_역할은_기존_DB_문자열과_구성원_응답을_유지한다(GroupRole role) {
        GroupMembership membership;
        if (role == GroupRole.PRIMARY) {
            membership = memberships.findByUserIdAndStatus(owner.getId(), GroupMembershipStatus.ACTIVE)
                    .orElseThrow();
        } else {
            membership = GroupMembership.member(group, user(), NOW);
            membership.changeDeputyRole(role, NOW);
            em.persist(membership);
        }
        em.flush();
        Long membershipId = membership.getId();
        Long groupId = group.getId();
        em.clear();

        // 실제 DB를 다시 읽어 Enum 매핑과 기존 저장 문자열을 확인합니다.
        assertThat(memberships.findById(membershipId).orElseThrow().getRole()).isEqualTo(role);
        assertThat(em.createNativeQuery("SELECT role FROM group_memberships WHERE id = :id", String.class)
                .setParameter("id", membershipId).getSingleResult()).isEqualTo(role.name());

        // 역할·상태 Enum을 받는 JPQL 생성자가 기존 API 문자열을 반환해야 합니다.
        var page = memberships.findActiveMemberResponsesByGroupId(groupId, PageRequest.of(0, 20));
        assertThat(page.getContent()).filteredOn(row -> row.membershipId().equals(membershipId))
                .singleElement().satisfies(row -> {
                    assertThat(row.role()).isEqualTo(role.name());
                    assertThat(row.status()).isEqualTo("ACTIVE");
                });
        assertThat(page.getTotalElements()).isEqualTo(role == GroupRole.PRIMARY ? 1 : 2);
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
        ReflectionTestUtils.setField(season, "status", SeasonStatus.ENDED);
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
        ReflectionTestUtils.setField(season, "status", SeasonStatus.ENDED);
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
        User user = User.register(GlobalRole.USER, NOW);
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
            membership = em.createQuery("select m from GroupMembership m where m.user.id = :id and m.status = :status", GroupMembership.class)
                    .setParameter("status", GroupMembershipStatus.ACTIVE)
                    .setParameter("id", owner.getId()).getSingleResult();
        } else {
            membership = GroupMembership.member(group, user, NOW.minusSeconds(60));
            if (!"ACTIVE".equals(status)) {
                ReflectionTestUtils.setField(membership, "status", GroupMembershipStatus.valueOf(status));
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
