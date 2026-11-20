package com.team.sharedcreature.quest.api;

import com.team.sharedcreature.quest.domain.QuestPeriodType;
import com.team.sharedcreature.quest.domain.QuestPeriodStatus;

import com.team.sharedcreature.season.domain.CreatureGrowthStage;

import com.team.sharedcreature.season.domain.GroupSeasonStatus;

import com.team.sharedcreature._core.exception.BusinessException;
import com.team.sharedcreature._core.exception.ErrorCode;
import com.team.sharedcreature.auth.domain.User;
import com.team.sharedcreature.auth.infrastructure.UserRepository;
import com.team.sharedcreature.group.domain.Group;
import com.team.sharedcreature.group.domain.GroupMembership;
import com.team.sharedcreature.group.infrastructure.GroupMembershipRepository;
import com.team.sharedcreature.quest.domain.QuestAssignment;
import com.team.sharedcreature.quest.domain.QuestCompletion;
import com.team.sharedcreature.quest.domain.QuestPeriod;
import com.team.sharedcreature.quest.domain.QuestRewardLedger;
import com.team.sharedcreature.quest.domain.QuestRewardType;
import com.team.sharedcreature.quest.domain.QuestVerificationType;
import com.team.sharedcreature.quest.infrastructure.PhotoSubmissionRepository;
import com.team.sharedcreature.quest.infrastructure.QuestAssignmentRepository;
import com.team.sharedcreature.quest.infrastructure.QuestCompletionRepository;
import com.team.sharedcreature.quest.infrastructure.QuestPeriodRepository;
import com.team.sharedcreature.quest.infrastructure.QuestRewardLedgerRepository;
import com.team.sharedcreature.quest.infrastructure.ServiceCheckInRepository;
import com.team.sharedcreature.season.application.SeasonQueryService;
import com.team.sharedcreature.season.application.CreatureUpdateEventPublisher;
import com.team.sharedcreature.season.domain.CreatureExperienceLedger;
import com.team.sharedcreature.season.domain.GroupCreature;
import com.team.sharedcreature.season.domain.GroupSeason;
import com.team.sharedcreature.season.domain.Season;
import com.team.sharedcreature.season.domain.SeasonGroupScore;
import com.team.sharedcreature.season.domain.SeasonMemberContribution;
import com.team.sharedcreature.season.infrastructure.CreatureExperienceLedgerRepository;
import com.team.sharedcreature.season.infrastructure.GroupCreatureRepository;
import com.team.sharedcreature.season.infrastructure.GroupSeasonRepository;
import com.team.sharedcreature.season.infrastructure.SeasonGroupScoreRepository;
import com.team.sharedcreature.season.infrastructure.SeasonMemberContributionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.junit.jupiter.MockitoExtension;
import tools.jackson.databind.ObjectMapper;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class QuestCompletionServiceTest {

    @Captor private ArgumentCaptor<Iterable<QuestRewardLedger>> rewardBatch;

    private static final Instant NOW = Instant.parse("2026-11-05T01:00:00Z");

    @Mock private SeasonMemberContributionRepository memberContributionRepository;
    @Mock private SeasonGroupScoreRepository groupScoreRepository;
    @Mock private QuestRewardLedgerRepository rewardLedgerRepository;
    @Mock private GroupCreatureRepository groupCreatureRepository;
    @Mock private CreatureExperienceLedgerRepository creatureExperienceLedgerRepository;
    @Mock private UserRepository userRepository;
    @Mock private GroupSeasonRepository groupSeasonRepository;
    @Mock private GroupMembershipRepository membershipRepository;
    @Mock private ServiceCheckInRepository serviceCheckInRepository;
    @Mock private SeasonQueryService seasonQueryService;
    @Mock private CreatureUpdateEventPublisher creatureUpdateEventPublisher;
    @Mock private com.team.sharedcreature.quest.application.ContributionUpdateEventPublisher contributionUpdateEventPublisher;
    @Mock private QuestPeriodRepository periodRepository;
    @Mock private QuestAssignmentRepository assignmentRepository;
    @Mock private QuestCompletionRepository completionRepository;
    @Mock private PhotoSubmissionRepository photoSubmissionRepository;

    private QuestCompletionService service;
    private User user;
    private Group group;
    private GroupMembership membership;
    private Season season;
    private GroupSeason groupSeason;
    private QuestPeriod period;
    private QuestAssignment assignment;
    private GroupCreature creature;
    private SeasonMemberContribution contribution;
    private SeasonGroupScore groupScore;
    private QuestCompletion completion;

    @BeforeEach
    void setUp() {
        service = new QuestCompletionService(
                memberContributionRepository,
                groupScoreRepository,
                rewardLedgerRepository,
                groupCreatureRepository,
                creatureExperienceLedgerRepository,
                userRepository,
                groupSeasonRepository,
                membershipRepository,
                serviceCheckInRepository,
                seasonQueryService,
                creatureUpdateEventPublisher,
                contributionUpdateEventPublisher,
                periodRepository,
                assignmentRepository,
                completionRepository,
                photoSubmissionRepository,
                Clock.fixed(NOW, ZoneOffset.UTC),
                mock(ObjectMapper.class)
        );

        user = mock(User.class);
        group = mock(Group.class);
        membership = mock(GroupMembership.class);
        season = mock(Season.class);
        groupSeason = mock(GroupSeason.class);
        period = mock(QuestPeriod.class);
        assignment = mock(QuestAssignment.class);
        creature = mock(GroupCreature.class);
        contribution = mock(SeasonMemberContribution.class);
        groupScore = mock(SeasonGroupScore.class);
        completion = mock(QuestCompletion.class);
    }

    @ParameterizedTest
    @NullSource
    @EnumSource(QuestPeriodType.class)
    void listsCurrentAssignmentsForRequestedPeriods(QuestPeriodType filter) {
        givenCurrentGroupSeason();

        assertThat(service.listCurrentQuests(7L, filter == null ? null : filter.name())).isEmpty();

        verify(periodRepository, times(filter == null || filter == QuestPeriodType.DAILY ? 1 : 0))
                .findCurrentBySeasonAndType(21L, QuestPeriodType.DAILY, NOW);
        verify(periodRepository, times(filter == null || filter == QuestPeriodType.WEEKLY ? 1 : 0))
                .findCurrentBySeasonAndType(21L, QuestPeriodType.WEEKLY, NOW);
    }

    @ParameterizedTest
    @ValueSource(strings = {"daily", "", " WEEKLY ", "MONTHLY"})
    void rejectsInvalidPeriodBeforeLookingUpAssignments(String filter) {
        givenCurrentGroupSeason();

        assertThatThrownBy(() -> service.listCurrentQuests(7L, filter))
                .isInstanceOfSatisfying(BusinessException.class, exception ->
                        assertThat(exception.errorCode()).isEqualTo(ErrorCode.INVALID_FIELD_VALUE));

        verifyNoInteractions(periodRepository, assignmentRepository);
    }

    private void givenCurrentGroupSeason() {
        when(membershipRepository.findActiveWithGroupByUserId(7L)).thenReturn(Optional.of(membership));
        when(membership.getGroup()).thenReturn(group);
        when(group.getId()).thenReturn(11L);
        when(seasonQueryService.findCurrentSeason()).thenReturn(season);
        when(season.getId()).thenReturn(21L);
        when(groupSeasonRepository.findSnapshotByGroupIdAndSeasonId(11L, 21L))
                .thenReturn(Optional.of(groupSeason));
    }

    @ParameterizedTest
    @EnumSource(value = GroupSeasonStatus.class, names = {"PREPARING", "ACTIVE"})
    void 부화전과_부화후_완료_보상은_배정_Snapshot의_경험치와_기여도를_사용한다(
            GroupSeasonStatus groupSeasonStatus
    ) {
        givenValidSelfCheckAssignment(groupSeasonStatus);
        when(completionRepository.findAllByUserIdAndQuestAssignmentIdIn(7L, List.of(11L)))
                .thenReturn(List.of());
        when(groupCreatureRepository.findForUpdateByGroupSeasonId(31L))
                .thenReturn(Optional.of(creature));
        when(memberContributionRepository.findByGroupSeasonIdAndUserId(31L, 7L))
                .thenReturn(Optional.of(contribution));
        when(groupScoreRepository.findForUpdateByGroupSeasonId(31L)).thenReturn(Optional.of(groupScore));
        when(completionRepository.save(any(QuestCompletion.class))).thenReturn(completion);
        when(completion.getId()).thenReturn(101L);
        when(completion.getCompletedAt()).thenReturn(NOW);
        when(creature.getTotalExperience()).thenReturn(800L);
        when(creature.getGrowthStage()).thenReturn(CreatureGrowthStage.MATURE);
        when(groupScore.getScore()).thenReturn(120L);

        QuestCompletionResponse response = service.completeQuestAssignment(7L, 11L);

        assertThat(response.getCreatureExperienceAwarded()).isEqualTo(300L);
        assertThat(response.getContributionAwarded()).isEqualTo(60L);
        assertThat(response.getCreatureTotalExperience()).isEqualTo(800L);
        assertThat(response.getGroupSeasonScore()).isEqualTo(120L);
        // 원장 두 건을 한 번에 저장하는 실제 호출의 목록을 검증합니다.
        verify(rewardLedgerRepository).saveAll(rewardBatch.capture());
        assertThat(rewardBatch.getValue()).extracting(QuestRewardLedger::getRewardType)
                .containsExactly(QuestRewardType.CREATURE_EXPERIENCE, QuestRewardType.CONTRIBUTION);
        assertThat(rewardBatch.getValue()).extracting(QuestRewardLedger::getAmount).containsExactly(300L, 60L);
        verify(creature).addExperience(300L, NOW);
        verify(contribution).addContribution(60L, membership, NOW);
        verify(groupScore).addScore(60L, NOW);
        verify(creatureExperienceLedgerRepository).save(any());
        verify(rewardLedgerRepository).saveAll(any());
        verify(creatureUpdateEventPublisher).publishAfterCommit(any());
    }

    @Test
    void 종료된_그룹_시즌은_기간_종료와_구분해_거절한다() {
        givenValidSelfCheckAssignment(GroupSeasonStatus.ENDED);
        when(completionRepository.findAllByUserIdAndQuestAssignmentIdIn(7L, List.of(11L)))
                .thenReturn(List.of());

        assertThatThrownBy(() -> service.completeQuestAssignment(7L, 11L))
                .isInstanceOfSatisfying(BusinessException.class, exception ->
                        assertThat(exception.errorCode()).isEqualTo(ErrorCode.GROUP_SEASON_ENDED));

        verifyNoInteractions(rewardLedgerRepository, creatureExperienceLedgerRepository);
    }

    @ParameterizedTest
    @MethodSource("savedAndLegacyGrowthStages")
    void 이미_완료한_배정은_시즌이_끝난_뒤에도_기존_결과를_반환한다(
            CreatureGrowthStage stage, Instant hatchedAt, CreatureGrowthStage expectedStage
    ) {
        when(user.getId()).thenReturn(7L);
        when(membership.getId()).thenReturn(41L);
        when(userRepository.findByIdForUpdate(7L)).thenReturn(Optional.of(user));
        when(membershipRepository.findActiveWithGroupByUserId(7L))
                .thenReturn(Optional.of(membership));
        when(assignment.getId()).thenReturn(11L);
        when(assignment.getVerificationTypeSnapshot()).thenReturn(QuestVerificationType.SELF_CHECK);
        when(assignmentRepository.findWithSnapshotById(11L)).thenReturn(Optional.of(assignment));
        when(completion.getMembership()).thenReturn(membership);
        when(completion.getId()).thenReturn(101L);
        when(completion.getQuestAssignment()).thenReturn(assignment);
        when(completion.getUser()).thenReturn(user);
        when(completion.getCompletedAt()).thenReturn(NOW.minusSeconds(3600));
        when(completion.getCreatureTotalExperienceAfter()).thenReturn(800L);
        when(completion.getCreatureGrowthStageAfter()).thenReturn(stage);
        when(completion.getGroupSeasonScoreAfter()).thenReturn(120L);
        when(completionRepository.findAllByUserIdAndQuestAssignmentIdIn(7L, List.of(11L)))
                .thenReturn(List.of(completion));
        when(rewardLedgerRepository.findAllByQuestCompletionId(101L)).thenReturn(List.of(
                QuestRewardLedger.create(completion, QuestRewardType.CREATURE_EXPERIENCE, 300,
                        creature, null, null, NOW.minusSeconds(3600)),
                QuestRewardLedger.create(completion, QuestRewardType.CONTRIBUTION, 60,
                        null, contribution, groupScore, NOW.minusSeconds(3600))
        ));
        when(creatureExperienceLedgerRepository.findByQuestCompletionId(101L))
                .thenReturn(Optional.of(mock(CreatureExperienceLedger.class)));

        // 과거에 단계 기록이 없던 완료도 기존 원장과 부화 이력으로 응답을 복원합니다.
        if (stage == null) {
            when(completion.getGroupSeason()).thenReturn(groupSeason);
            when(groupSeason.getId()).thenReturn(31L);
            when(groupCreatureRepository.findSnapshotByGroupSeasonId(31L)).thenReturn(Optional.of(creature));
            when(creature.getHatchedAt()).thenReturn(hatchedAt);
        }

        QuestCompletionResponse response = service.completeQuestAssignment(7L, 11L);

        assertThat(response.getCompletionId()).isEqualTo(101L);
        assertThat(response.getCompletedAt()).isEqualTo(NOW.minusSeconds(3600));
        assertThat(response.getCreatureTotalExperience()).isEqualTo(800L);
        assertThat(response.getCreatureGrowthStage()).isEqualTo(expectedStage);
        assertThat(response.getCreatureExperienceAwarded()).isEqualTo(300L);
        assertThat(response.getContributionAwarded()).isEqualTo(60L);
        assertThat(response.getGroupSeasonScore()).isEqualTo(120L);
        verify(seasonQueryService, never()).findCurrentSeason();
        verify(creatureUpdateEventPublisher, never()).publishAfterCommit(any());
    }

    /** 저장된 다섯 단계와 과거 미기록 데이터의 부화 전·후 복원 경로를 검증합니다. */
    private static java.util.stream.Stream<Arguments> savedAndLegacyGrowthStages() {
        Stream<Arguments> saved = java.util.Arrays.stream(CreatureGrowthStage.values())
                .map(stage -> Arguments.of(stage, null, stage));
        Stream<Arguments> legacy = java.util.stream.Stream.of(
                Arguments.of(null, null, CreatureGrowthStage.EGG),
                Arguments.of(null, NOW.minusSeconds(7_200), CreatureGrowthStage.HATCHLING),
                Arguments.of(null, NOW.minusSeconds(1_800), CreatureGrowthStage.EGG)
        );
        return java.util.stream.Stream.concat(saved, legacy);
    }

    private void givenValidSelfCheckAssignment(GroupSeasonStatus groupSeasonStatus) {
        when(group.getId()).thenReturn(9L);
        when(membership.getGroup()).thenReturn(group);
        when(season.getId()).thenReturn(3L);
        when(groupSeason.getStatus()).thenReturn(groupSeasonStatus);
        when(period.getSeason()).thenReturn(season);
        when(period.getStatus()).thenReturn(QuestPeriodStatus.ACTIVE);
        when(period.getStartAt()).thenReturn(NOW.minusSeconds(60));
        when(period.getEndAt()).thenReturn(NOW.plusSeconds(60));
        when(assignment.getId()).thenReturn(11L);
        when(assignment.getQuestPeriod()).thenReturn(period);
        when(assignment.getVerificationTypeSnapshot()).thenReturn(QuestVerificationType.SELF_CHECK);

        if (groupSeasonStatus != GroupSeasonStatus.ENDED) {
            when(user.getId()).thenReturn(7L);
            when(groupSeason.getId()).thenReturn(31L);
            when(groupSeason.getSeason()).thenReturn(season);
            when(groupSeason.getGroup()).thenReturn(group);
            when(membership.getId()).thenReturn(41L);
            when(period.getPeriodType()).thenReturn(QuestPeriodType.WEEKLY);
            when(assignment.getCreatureExperienceSnapshot()).thenReturn(300L);
            when(assignment.getContributionScoreSnapshot()).thenReturn(60L);
            when(creature.getGroupSeason()).thenReturn(groupSeason);
            when(creature.getId()).thenReturn(51L);
        }

        when(season.isActiveAt(NOW)).thenReturn(true);
        when(seasonQueryService.findCurrentSeasonForShareIfPresent())
                .thenReturn(Optional.of(season));
        when(userRepository.findByIdForUpdate(7L)).thenReturn(Optional.of(user));
        when(membershipRepository.findActiveWithGroupByUserId(7L))
                .thenReturn(Optional.of(membership));
        when(assignmentRepository.findWithSnapshotById(11L))
                .thenReturn(Optional.of(assignment));
        when(groupSeasonRepository.findForUpdateByGroupIdAndSeasonId(9L, 3L))
                .thenReturn(Optional.of(groupSeason));
    }
}
