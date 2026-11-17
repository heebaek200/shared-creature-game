package com.team.sharedcreature.ranking.integration;

import com.team.sharedcreature.season.domain.CreatureGrowthStage;

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
import com.team.sharedcreature.season.infrastructure.GroupCreatureRepository;
import com.team.sharedcreature.season.api.SeasonResponse;
import com.team.sharedcreature.season.application.CreatureUpdatedEvent;
import com.team.sharedcreature.quest.domain.QuestPeriod;
import com.team.sharedcreature.quest.domain.QuestPeriodStatus;
import com.team.sharedcreature.quest.infrastructure.QuestPeriodRepository;
import com.team.sharedcreature.quest.domain.QuestTemplate;
import com.team.sharedcreature.quest.domain.QuestTemplateStatus;
import com.team.sharedcreature.quest.domain.QuestDifficulty;
import com.team.sharedcreature.quest.domain.QuestVerificationType;
import com.team.sharedcreature.quest.domain.PhotoSubmission;
import com.team.sharedcreature.quest.domain.PhotoVerificationStatus;
import com.team.sharedcreature.quest.infrastructure.PhotoSubmissionRepository;
import com.team.sharedcreature.quest.domain.QuestAssignment;
import com.team.sharedcreature.quest.domain.QuestCompletion;
import com.team.sharedcreature.quest.domain.QuestTemplateVersion;
import com.team.sharedcreature.quest.infrastructure.QuestCompletionRepository;
import com.team.sharedcreature.quest.infrastructure.QuestTemplateRepository;
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
    @Autowired private GroupCreatureRepository creatures;
    @Autowired private QuestPeriodRepository periods;
    @Autowired private QuestTemplateRepository templates;
    @Autowired private QuestCompletionRepository completions;
    @Autowired private PhotoSubmissionRepository photos;
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

    /** 사진 상태의 저장 코드·정리 대상 조회와 판정 이력 보존을 검증합니다. */
    @ParameterizedTest
    @EnumSource(PhotoVerificationStatus.class)
    void 사진_판정_상태는_DB_코드와_정리_규칙을_유지한다(PhotoVerificationStatus status) {
        QuestAssignment assignment = questAssignment(QuestDifficulty.LOW, QuestVerificationType.PHOTO_EXIF);
        PhotoSubmission photo = PhotoSubmission.create(owner, assignment,
                "enum/photo/" + UUID.randomUUID(), 1, NOW.minusSeconds(7_200));
        if (status == PhotoVerificationStatus.VALID) {
            photo.markValid(NOW.minusSeconds(7_200), java.math.BigDecimal.ONE,
                    java.math.BigDecimal.ONE, NOW.minusSeconds(60));
        } else if (status == PhotoVerificationStatus.INVALID) {
            photo.markInvalid("TEST_FAILURE", null, null, null, NOW.minusSeconds(60));
        }
        em.persist(photo);
        em.flush();
        Long photoId = photo.getId();
        em.clear();

        PhotoSubmission reloaded = photos.findById(photoId).orElseThrow();
        assertThat(reloaded.getVerificationStatus()).isEqualTo(status);
        assertThat(em.createNativeQuery("SELECT verification_status FROM photo_submissions WHERE id = :id", String.class)
                .setParameter("id", photoId).getSingleResult()).isEqualTo(status.name());
        var stalePending = photos.findFreshStalePendingIds(NOW.minusSeconds(3_600), NOW, PageRequest.of(0, 1_000));
        var invalid = photos.findFreshInvalidIds(NOW, PageRequest.of(0, 1_000));
        if (status == PhotoVerificationStatus.PENDING) {
            assertThat(stalePending).contains(photoId);
            assertThat(invalid).doesNotContain(photoId);
            assertThatThrownBy(() -> reloaded.markPhotoAndLocationDataDeleted(NOW))
                    .isInstanceOf(IllegalStateException.class);
        } else {
            assertThat(stalePending).doesNotContain(photoId);
            if (status == PhotoVerificationStatus.INVALID) {
                assertThat(invalid).contains(photoId);
            } else {
                assertThat(invalid).doesNotContain(photoId);
            }
            // 파일·좌표를 정리해도 판정 결과와 최초 정리 시각은 보존합니다.
            reloaded.markPhotoAndLocationDataDeleted(NOW);
            reloaded.markPhotoAndLocationDataDeleted(NOW.plusSeconds(1));
            em.flush();
            em.clear();
            PhotoSubmission cleaned = photos.findById(photoId).orElseThrow();
            assertThat(cleaned.getVerificationStatus()).isEqualTo(status);
            assertThat(cleaned.getFileDeletedAt()).isEqualTo(NOW);
            assertThat(cleaned.getStorageKey()).isNull();
            assertThat(cleaned.getLatitude()).isNull();
            assertThat(cleaned.getLongitude()).isNull();
        }
    }

    /** 검증 유형의 저장 코드·완료 집계와 배정 기록의 독립성을 검증합니다. */
    @ParameterizedTest
    @EnumSource(QuestVerificationType.class)
    void 퀘스트_검증_유형은_DB_코드와_배정_기록_집계를_유지한다(QuestVerificationType type) {
        QuestAssignment assignment = questAssignment(QuestDifficulty.LOW, type);
        GroupMembership membership = memberships.findByUserIdAndStatus(owner.getId(), GroupMembershipStatus.ACTIVE)
                .orElseThrow();
        em.persist(QuestCompletion.create(owner, assignment, groupSeason, membership, NOW));
        em.flush();
        Long assignmentId = assignment.getId();
        Long versionId = assignment.getQuestTemplateVersion().getId();
        Long ownerId = owner.getId();
        Long membershipId = membership.getId();
        Long groupSeasonId = groupSeason.getId();
        var date = assignment.getQuestPeriod().getPeriodDate();
        em.clear();

        assertThat(em.find(QuestTemplateVersion.class, versionId).getVerificationType()).isEqualTo(type);
        assertThat(em.find(QuestAssignment.class, assignmentId).getVerificationTypeSnapshot()).isEqualTo(type);
        assertThat(em.createNativeQuery("SELECT verification_type FROM quest_template_versions WHERE id = :id", String.class)
                .setParameter("id", versionId).getSingleResult()).isEqualTo(type.name());
        assertThat(em.createNativeQuery("SELECT verification_type_snapshot FROM quest_assignments WHERE id = :id", String.class)
                .setParameter("id", assignmentId).getSingleResult()).isEqualTo(type.name());
        assertThat(completions.countDailyCompletionsByVerificationTypeInDateRange(ownerId, membershipId, groupSeasonId,
                type, date, date.plusDays(1))).isEqualTo(1);

        // 원본 버전 변경으로 이미 배정된 판정 방식이 덮어써지지 않아야 합니다.
        QuestVerificationType changed = type == QuestVerificationType.SELF_CHECK
                ? QuestVerificationType.PHOTO_EXIF : QuestVerificationType.SELF_CHECK;
        em.createQuery("UPDATE QuestTemplateVersion version SET version.verificationType = :type WHERE version.id = :id")
                .setParameter("type", changed).setParameter("id", versionId).executeUpdate();
        em.clear();
        assertThat(em.find(QuestAssignment.class, assignmentId).getVerificationTypeSnapshot()).isEqualTo(type);
        assertThat(completions.countDailyCompletionsByVerificationTypeInDateRange(ownerId, membershipId, groupSeasonId,
                type, date, date.plusDays(1))).isEqualTo(1);
        assertThat(completions.countDailyCompletionsByVerificationTypeInDateRange(ownerId, membershipId, groupSeasonId,
                changed, date, date.plusDays(1))).isZero();
    }

    /** 난이도의 저장 코드·배정 기록 보존·난이도별 완료 집계를 검증합니다. */
    @ParameterizedTest
    @EnumSource(QuestDifficulty.class)
    void 퀘스트_난이도는_DB_코드와_배정_기록_집계를_유지한다(QuestDifficulty difficulty) {
        QuestAssignment assignment = questAssignment(difficulty, QuestVerificationType.SELF_CHECK);
        QuestTemplate template = assignment.getQuestTemplate();
        var date = assignment.getQuestPeriod().getPeriodDate();
        GroupMembership membership = memberships.findByUserIdAndStatus(owner.getId(), GroupMembershipStatus.ACTIVE)
                .orElseThrow();
        em.persist(QuestCompletion.create(owner, assignment, groupSeason, membership, NOW));
        em.flush();
        Long templateId = template.getId();
        Long assignmentId = assignment.getId();
        Long ownerId = owner.getId();
        Long membershipId = membership.getId();
        Long groupSeasonId = groupSeason.getId();
        em.clear();

        assertThat(em.find(QuestTemplate.class, templateId).getDifficulty()).isEqualTo(difficulty);
        assertThat(em.find(QuestAssignment.class, assignmentId).getDifficultySnapshot()).isEqualTo(difficulty);
        assertThat(em.createNativeQuery("SELECT difficulty FROM quest_templates WHERE id = :id", String.class)
                .setParameter("id", templateId).getSingleResult()).isEqualTo(difficulty.name());
        assertThat(em.createNativeQuery("SELECT difficulty_snapshot FROM quest_assignments WHERE id = :id", String.class)
                .setParameter("id", assignmentId).getSingleResult()).isEqualTo(difficulty.name());
        assertThat(completions.countDailyCompletionsByDifficultyInDateRange(ownerId, membershipId, groupSeasonId,
                difficulty, date, date.plusDays(1))).isEqualTo(1);

        // 원본 난이도가 변경되어도 완료 집계는 배정 당시의 난이도를 사용합니다.
        QuestDifficulty changed = difficulty == QuestDifficulty.LOW ? QuestDifficulty.HIGH : QuestDifficulty.LOW;
        em.createQuery("UPDATE QuestTemplate template SET template.difficulty = :difficulty WHERE template.id = :id")
                .setParameter("difficulty", changed).setParameter("id", templateId).executeUpdate();
        em.clear();
        assertThat(em.find(QuestAssignment.class, assignmentId).getDifficultySnapshot()).isEqualTo(difficulty);
        assertThat(completions.countDailyCompletionsByDifficultyInDateRange(ownerId, membershipId, groupSeasonId,
                difficulty, date, date.plusDays(1))).isEqualTo(1);
        assertThat(completions.countDailyCompletionsByDifficultyInDateRange(ownerId, membershipId, groupSeasonId,
                changed, date, date.plusDays(1))).isZero();
    }

    /** 기존 템플릿 상태의 문자열 조회와 비활성·실험용 배정 제외를 검증합니다. */
    @ParameterizedTest
    @EnumSource(QuestTemplateStatus.class)
    void 퀘스트_템플릿_상태는_DB_코드와_활성_선정_조건을_유지한다(QuestTemplateStatus status) {
        String code = "enum-" + UUID.randomUUID().toString().substring(0, 24);
        int rotation = em.createQuery("""
                        SELECT COALESCE(MAX(template.rotationOrder), 0) FROM QuestTemplate template
                         WHERE template.periodType = 'DAILY' AND template.difficulty = com.team.sharedcreature.quest.domain.QuestDifficulty.LOW
                        """, Integer.class).getSingleResult() + 1;
        em.flush();
        // 기존 DB와 같은 문자열로 저장한 행이 Enum으로 조회되어야 합니다.
        em.createNativeQuery("""
                INSERT INTO quest_templates
                    (code, period_type, difficulty, status, rotation_order, created_at, updated_at)
                VALUES (:code, 'DAILY', 'LOW', :status, :rotation, :now, :now)
                """).setParameter("code", code).setParameter("status", status.name())
                .setParameter("rotation", rotation).setParameter("now", NOW).executeUpdate();
        em.clear();

        QuestTemplate template = em.createQuery("SELECT template FROM QuestTemplate template WHERE template.code = :code",
                        QuestTemplate.class).setParameter("code", code).getSingleResult();
        assertThat(template.getStatus()).isEqualTo(status);
        var selected = templates.findActiveByPeriodType("DAILY");
        assertThat(selected).allSatisfy(row -> assertThat(row.getStatus()).isEqualTo(QuestTemplateStatus.ACTIVE));
        if (status == QuestTemplateStatus.ACTIVE) {
            assertThat(selected).extracting(QuestTemplate::getId).contains(template.getId());
        } else {
            assertThat(selected).extracting(QuestTemplate::getId).doesNotContain(template.getId());
        }
    }

    /** 기간 상태의 기존 저장값·현재 기간 조회와 실패 재시도의 이력 보존을 검증합니다. */
    @ParameterizedTest
    @EnumSource(QuestPeriodStatus.class)
    void 퀘스트_기간_상태는_DB_조회와_실패_복구_계약을_유지한다(QuestPeriodStatus status) {
        Instant start = season.getStartAt();
        QuestPeriod period = QuestPeriod.pending(season, "DAILY",
                NOW.atZone(java.time.ZoneId.of("Asia/Seoul")).toLocalDate(),
                start, NOW.plusSeconds(3_600), NOW);
        if (status == QuestPeriodStatus.ACTIVE) {
            period.activate(NOW);
        } else if (status == QuestPeriodStatus.FAILED) {
            period.fail("TEST_FAILURE", "배정 실패 검증", NOW);
        } else if (status == QuestPeriodStatus.CLOSED) {
            // 종료 상태로 저장된 기존 기간의 조회 호환성을 검증합니다.
            ReflectionTestUtils.setField(period, "status", QuestPeriodStatus.CLOSED);
        }
        em.persist(period);
        em.flush();
        Long periodId = period.getId();
        Long seasonId = season.getId();
        em.clear();

        QuestPeriod reloaded = periods.findForUpdateByPeriodTypeAndStartAt("DAILY", start).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(status);
        assertThat(periods.findByPeriodTypeAndStartAt("DAILY", start).orElseThrow().getId()).isEqualTo(periodId);
        assertThat(em.createNativeQuery("SELECT status FROM quest_periods WHERE id = :id", String.class)
                .setParameter("id", periodId).getSingleResult()).isEqualTo(status.name());
        var current = periods.findCurrentBySeasonAndType(seasonId, "DAILY", NOW);
        if (status == QuestPeriodStatus.ACTIVE) {
            assertThat(current).contains(reloaded);
        } else {
            assertThat(current).isEmpty();
        }

        if (status == QuestPeriodStatus.FAILED) {
            assertThat(reloaded.getFailureCode()).isEqualTo("TEST_FAILURE");
            reloaded.prepareRetry(NOW.plusSeconds(1));
            reloaded.activate(NOW.plusSeconds(2));
            em.flush();
            em.clear();
            QuestPeriod recovered = periods.findById(periodId).orElseThrow();
            assertThat(recovered.getStatus()).isEqualTo(QuestPeriodStatus.ACTIVE);
            assertThat(recovered.getFailureCode()).isNull();
            assertThat(recovered.getFailureDetail()).isNull();
            assertThat(recovered.getCreatedAt()).isEqualTo(NOW);
        } else if (status == QuestPeriodStatus.ACTIVE || status == QuestPeriodStatus.CLOSED) {
            assertThatThrownBy(() -> reloaded.prepareRetry(NOW.plusSeconds(1)))
                    .isInstanceOf(IllegalStateException.class);
            assertThat(reloaded.getStatus()).isEqualTo(status);
        }
    }

    /** 참여 상태의 DB 코드와 종료 순위의 상태 보존을 검증합니다. */
    @ParameterizedTest
    @EnumSource(GroupMembershipStatus.class)
    void 시즌_참여_상태와_종료_순위는_DB와_API의_기존_코드를_유지한다(GroupMembershipStatus status) {
        User target = user();
        SeasonMemberContribution original = contribution(target, 100, NOW, status.name());
        UserProfile profile = em.createQuery("SELECT profile FROM UserProfile profile WHERE profile.user.id = :id",
                        UserProfile.class)
                .setParameter("id", target.getId()).getSingleResult();
        SeasonMemberRankSnapshot snapshot = SeasonMemberRankSnapshot.capture(
                season, groupSeason, original, profile,
                new SeasonRankingCalculator.MemberRank(1, target.getId(), 100, NOW), NOW
        );
        em.persist(snapshot);
        em.flush();
        Long contributionId = original.getId();
        Long snapshotId = snapshot.getId();
        Long seasonId = season.getId();
        Long ownerId = owner.getId();
        em.clear();

        assertThat(em.find(SeasonMemberContribution.class, contributionId).getParticipantStatus()).isEqualTo(status);
        assertThat(em.find(SeasonMemberRankSnapshot.class, snapshotId).getParticipantStatus()).isEqualTo(status);
        assertThat(em.createNativeQuery("SELECT participant_status FROM season_member_contributions WHERE id = :id", String.class)
                .setParameter("id", contributionId).getSingleResult()).isEqualTo(status.name());
        assertThat(em.createNativeQuery("SELECT participant_status FROM season_member_rank_snapshots WHERE id = :id", String.class)
                .setParameter("id", snapshotId).getSingleResult()).isEqualTo(status.name());
        assertThat(service.getMemberRanking(ownerId, seasonId, 0, 20).data())
                .singleElement().satisfies(row -> {
                    assertThat(row.participantStatus()).isEqualTo(status.name());
                    assertThat(row.contributionScore()).isEqualTo(100);
                    assertThat(row.scoreReachedAt()).isEqualTo(NOW);
                });

        // 종료 이후 원본 참가 상태가 바뀌어도 확정 순위는 기존 상태를 반환해야 합니다.
        // 조회된 엔티티는 프록시일 수 있으므로 필드 리플렉션 대신 DB에 종료 상태를 명시합니다.
        em.createQuery("UPDATE Season season SET season.status = :status WHERE season.id = :id")
                .setParameter("status", SeasonStatus.ENDED).setParameter("id", seasonId).executeUpdate();
        GroupMembershipStatus changed = status == GroupMembershipStatus.ACTIVE
                ? GroupMembershipStatus.LEFT : GroupMembershipStatus.ACTIVE;
        em.createQuery("UPDATE SeasonMemberContribution contribution SET contribution.participantStatus = :status WHERE contribution.id = :id")
                .setParameter("status", changed).setParameter("id", contributionId).executeUpdate();
        em.flush();
        em.clear();
        assertThat(em.find(Season.class, seasonId).getStatus()).isEqualTo(SeasonStatus.ENDED);
        assertThat(em.find(SeasonMemberContribution.class, contributionId).getParticipantStatus()).isEqualTo(changed);
        assertThat(em.find(SeasonMemberRankSnapshot.class, snapshotId).getParticipantStatus()).isEqualTo(status);
        assertThat(service.getMemberRanking(ownerId, seasonId, 0, 20).data())
                .singleElement().satisfies(row -> {
                    assertThat(row.participantStatus()).isEqualTo(status.name());
                    assertThat(row.contributionScore()).isEqualTo(100);
                });
    }

    /** 성장 단계의 저장값·경계값·이벤트·관리자 집계의 문자열 계약을 검증합니다. */
    @ParameterizedTest
    @CsvSource({
            "EGG,50000,0,45000",
            "HATCHLING,0,0,45000",
            "GROWING,45000,45000,97500",
            "MATURE,97500,97500,150000",
            "COMPLETE,150000,150000,"
    })
    void 성장_단계는_DB와_이벤트_집계의_문자열_계약을_유지한다(
            CreatureGrowthStage stage, long experience, long currentThreshold, Long nextThreshold
    ) {
        GroupCreature creature = GroupCreature.egg(groupSeason, NOW);
        if (experience > 0) {
            creature.addExperience(experience, NOW);
        }
        if (stage != CreatureGrowthStage.EGG) {
            CreatureSpecies species = species();
            groupSeason.selectRandomSpecies(species, NOW);
            creature.hatch(species, groupSeason.getSelectionSource(), NOW);
            groupSeason.activate(NOW);
        }
        em.persist(creature);
        em.flush();
        Long creatureId = creature.getId();
        Long groupId = group.getId();
        em.clear();

        GroupCreature reloaded = em.find(GroupCreature.class, creatureId);
        assertThat(reloaded.getGrowthStage()).isEqualTo(stage);
        assertThat(reloaded.getTotalExperience()).isEqualTo(experience);
        assertThat(reloaded.currentStageThreshold()).isEqualTo(currentThreshold);
        assertThat(reloaded.nextStageThreshold()).isEqualTo(nextThreshold);
        assertThat(em.createNativeQuery("SELECT growth_stage FROM group_creatures WHERE id = :id", String.class)
                .setParameter("id", creatureId).getSingleResult()).isEqualTo(stage.name());
        assertThat(CreatureUpdatedEvent.from(reloaded, NOW).stage()).isEqualTo(stage.name());
        assertThat(analytics.growthStages(NOW, groupId)).containsOnlyKeys(stage.name())
                .containsEntry(stage.name(), 1L);
    }

    /** 운영·보관 상태의 저장값과 보관 처리의 이력 보존·멱등성을 검증합니다. */
    @ParameterizedTest
    @EnumSource(GroupCreatureStatus.class)
    void 공동_크리처의_상태와_보관_이력은_DB에서_유지된다(GroupCreatureStatus status) {
        GroupCreature creature = GroupCreature.egg(groupSeason, NOW);
        creature.addExperience(50_000L, NOW);
        if (status == GroupCreatureStatus.ARCHIVED) {
            creature.archiveForGroupClosure(NOW.plusSeconds(1));
            creature.archiveForGroupClosure(NOW.plusSeconds(2));
        }
        em.persist(creature);
        em.flush();
        Long creatureId = creature.getId();
        Long groupSeasonId = groupSeason.getId();
        em.clear();

        GroupCreature reloaded = creatures.findSnapshotByGroupSeasonId(groupSeasonId).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(status);
        assertThat(creatures.findForUpdateByGroupSeasonId(groupSeasonId)).contains(reloaded);
        assertThat(em.createNativeQuery("SELECT status FROM group_creatures WHERE id = :id", String.class)
                .setParameter("id", creatureId).getSingleResult()).isEqualTo(status.name());
        // 보관해도 알 상태·경험치·미선택 정보를 유지하고 반복 보관은 시각을 변경하지 않습니다.
        assertThat(reloaded.getTotalExperience()).isEqualTo(50_000L);
        assertThat(reloaded.getGrowthStage()).isEqualTo(CreatureGrowthStage.EGG);
        assertThat(reloaded.getSelectionSource()).isNull();
        assertThat(reloaded.getHatchedAt()).isNull();
        assertThat(reloaded.getUpdatedAt())
                .isEqualTo(status == GroupCreatureStatus.ARCHIVED ? NOW.plusSeconds(1) : NOW);
    }

    /** 미선택·관리자 선택·무작위 선택의 출처가 두 기록에 동일하게 저장되는지 검증합니다. */
    @ParameterizedTest
    @EnumSource(CreatureSelectionSource.class)
    @NullSource
    void 종_선택_출처는_미선택_null과_부화_후_DB_코드를_유지한다(CreatureSelectionSource source) {
        GroupCreature creature = GroupCreature.egg(groupSeason, NOW);
        if (source != null) {
            CreatureSpecies species = species();
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
        em.createQuery("update SeasonMemberContribution c set c.contributionScore = 999, c.participantStatus = com.team.sharedcreature.group.domain.GroupMembershipStatus.REMOVED where c.id = :id")
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

    /** 난이도·검증 유형의 배정 기록을 실제 FK와 함께 준비합니다. */
    private QuestAssignment questAssignment(QuestDifficulty difficulty, QuestVerificationType verificationType) {
        QuestTemplate template = questTemplate(difficulty);
        QuestTemplateVersion version = org.springframework.beans.BeanUtils.instantiateClass(QuestTemplateVersion.class);
        ReflectionTestUtils.setField(version, "questTemplate", template);
        ReflectionTestUtils.setField(version, "versionNumber", 1);
        ReflectionTestUtils.setField(version, "title", "배정 기록 검증");
        ReflectionTestUtils.setField(version, "description", "원본 변경 이후 보존 검증");
        ReflectionTestUtils.setField(version, "verificationType", verificationType);
        ReflectionTestUtils.setField(version, "verificationParameters", "{}");
        ReflectionTestUtils.setField(version, "creatureExperience", 100L);
        ReflectionTestUtils.setField(version, "contributionScore", 10L);
        ReflectionTestUtils.setField(version, "createdAt", NOW);
        em.persist(version);
        var date = NOW.atZone(java.time.ZoneId.of("Asia/Seoul")).toLocalDate();
        QuestPeriod period = QuestPeriod.pending(season, "DAILY", date,
                season.getStartAt(), NOW.plusSeconds(3600), NOW);
        period.activate(NOW);
        em.persist(period);
        QuestAssignment assignment = QuestAssignment.capture(period, template, version, 1,
                new QuestAssignment.Snapshot("배정 검증", "배정 기록", difficulty, verificationType, "{}", 100, 10), NOW);
        em.persist(assignment);
        return assignment;
    }

    /** 난이도가 바뀌어도 기존 카탈로그의 유일 제약과 충돌하지 않는 템플릿을 준비합니다. */
    private QuestTemplate questTemplate(QuestDifficulty difficulty) {
        int rotation = em.createQuery("SELECT COALESCE(MAX(template.rotationOrder), 0) FROM QuestTemplate template",
                Integer.class).getSingleResult() + 1;
        QuestTemplate template = org.springframework.beans.BeanUtils.instantiateClass(QuestTemplate.class);
        ReflectionTestUtils.setField(template, "code", "enum-" + UUID.randomUUID().toString().substring(0, 24));
        ReflectionTestUtils.setField(template, "periodType", "DAILY");
        ReflectionTestUtils.setField(template, "difficulty", difficulty);
        ReflectionTestUtils.setField(template, "status", QuestTemplateStatus.ACTIVE);
        ReflectionTestUtils.setField(template, "rotationOrder", rotation);
        ReflectionTestUtils.setField(template, "createdAt", NOW);
        ReflectionTestUtils.setField(template, "updatedAt", NOW);
        em.persist(template);
        return template;
    }

    /** 선택·부화 검증에서 기존 초기 데이터와 충돌하지 않는 활성 종을 준비합니다. */
    private CreatureSpecies species() {
        CreatureSpecies species = org.springframework.beans.BeanUtils.instantiateClass(CreatureSpecies.class);
        ReflectionTestUtils.setField(species, "code", "source-" + UUID.randomUUID());
        ReflectionTestUtils.setField(species, "name", "선택·성장 검증");
        ReflectionTestUtils.setField(species, "assetKey", "species/test-source");
        ReflectionTestUtils.setField(species, "status", CreatureSpeciesStatus.ACTIVE);
        ReflectionTestUtils.setField(species, "createdAt", NOW);
        ReflectionTestUtils.setField(species, "updatedAt", NOW);
        em.persist(species);
        return species;
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
