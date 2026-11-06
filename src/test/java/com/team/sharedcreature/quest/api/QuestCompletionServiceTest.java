package com.team.sharedcreature.quest.api;

import com.team.sharedcreature.auth.domain.User;
import com.team.sharedcreature.auth.infrastructure.UserRepository;
import com.team.sharedcreature.group.domain.Group;
import com.team.sharedcreature.group.domain.GroupMembership;
import com.team.sharedcreature.group.infrastructure.GroupMembershipRepository;
import com.team.sharedcreature.quest.domain.QuestAssignment;
import com.team.sharedcreature.quest.domain.QuestCompletion;
import com.team.sharedcreature.quest.domain.QuestPeriod;
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
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import tools.jackson.databind.ObjectMapper;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class QuestCompletionServiceTest {

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
    @Mock private QuestPeriodRepository periodRepository;
    @Mock private QuestAssignmentRepository assignmentRepository;
    @Mock private QuestCompletionRepository completionRepository;

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
                periodRepository,
                assignmentRepository,
                completionRepository,
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

    @Test
    void 완료_보상은_요청값이_아닌_배정_Snapshot의_경험치와_기여도를_사용한다() {
        givenValidSelfCheckAssignment();
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
        when(creature.getGrowthStage()).thenReturn("MATURE");
        when(groupScore.getScore()).thenReturn(120L);

        QuestCompletionResponse response = service.completeQuestAssignment(7L, 11L);

        assertThat(response.getCreatureExperienceAwarded()).isEqualTo(300L);
        assertThat(response.getContributionAwarded()).isEqualTo(60L);
        assertThat(response.getCreatureTotalExperience()).isEqualTo(800L);
        assertThat(response.getGroupSeasonScore()).isEqualTo(120L);
        verify(creature).addExperience(300L, NOW);
        verify(contribution).addContribution(60L, membership, NOW);
        verify(groupScore).addScore(60L, NOW);
        verify(creatureExperienceLedgerRepository).save(any());
        verify(rewardLedgerRepository).saveAll(any());
        verify(creatureUpdateEventPublisher).publishAfterCommit(any());
    }

    @Test
    void 이미_완료한_배정은_시즌이_끝난_뒤에도_기존_결과를_반환한다() {
        when(user.getId()).thenReturn(7L);
        when(membership.getId()).thenReturn(41L);
        when(userRepository.findByIdForUpdate(7L)).thenReturn(Optional.of(user));
        when(membershipRepository.findActiveWithGroupByUserId(7L))
                .thenReturn(Optional.of(membership));
        when(seasonQueryService.findCurrentSeasonForShareIfPresent()).thenReturn(Optional.empty());
        when(assignment.getId()).thenReturn(11L);
        when(assignment.getVerificationTypeSnapshot()).thenReturn("SELF_CHECK");
        when(assignmentRepository.findWithSnapshotById(11L)).thenReturn(Optional.of(assignment));
        when(completion.getMembership()).thenReturn(membership);
        when(completion.getId()).thenReturn(101L);
        when(completion.getQuestAssignment()).thenReturn(assignment);
        when(completion.getUser()).thenReturn(user);
        when(completion.getCompletedAt()).thenReturn(NOW.minusSeconds(3600));
        when(completion.getCreatureTotalExperienceAfter()).thenReturn(800L);
        when(completion.getCreatureGrowthStageAfter()).thenReturn("MATURE");
        when(completion.getGroupSeasonScoreAfter()).thenReturn(120L);
        when(completionRepository.findAllByUserIdAndQuestAssignmentIdIn(7L, List.of(11L)))
                .thenReturn(List.of(completion));
        when(rewardLedgerRepository.findAllByQuestCompletionId(101L)).thenReturn(List.of());
        when(creatureExperienceLedgerRepository.findByQuestCompletionId(101L))
                .thenReturn(Optional.of(mock(CreatureExperienceLedger.class)));

        QuestCompletionResponse response = service.completeQuestAssignment(7L, 11L);

        assertThat(response.getCompletionId()).isEqualTo(101L);
        assertThat(response.getCompletedAt()).isEqualTo(NOW.minusSeconds(3600));
        assertThat(response.getCreatureTotalExperience()).isEqualTo(800L);
        assertThat(response.getCreatureGrowthStage()).isEqualTo("MATURE");
        assertThat(response.getGroupSeasonScore()).isEqualTo(120L);
        verify(seasonQueryService, never()).findCurrentSeason();
        verify(creatureUpdateEventPublisher, never()).publishAfterCommit(any());
    }

    private void givenValidSelfCheckAssignment() {
        when(user.getId()).thenReturn(7L);
        when(group.getId()).thenReturn(9L);
        when(membership.getGroup()).thenReturn(group);
        when(season.getId()).thenReturn(3L);
        when(groupSeason.getId()).thenReturn(31L);
        when(groupSeason.getStatus()).thenReturn("ACTIVE");
        when(groupSeason.getSeason()).thenReturn(season);
        when(groupSeason.getGroup()).thenReturn(group);
        when(membership.getId()).thenReturn(41L);
        when(period.getSeason()).thenReturn(season);
        when(period.getStatus()).thenReturn("ACTIVE");
        when(period.getPeriodType()).thenReturn("WEEKLY");
        when(period.getStartAt()).thenReturn(NOW.minusSeconds(60));
        when(period.getEndAt()).thenReturn(NOW.plusSeconds(60));
        when(assignment.getId()).thenReturn(11L);
        when(assignment.getQuestPeriod()).thenReturn(period);
        when(assignment.getVerificationTypeSnapshot()).thenReturn("SELF_CHECK");
        when(assignment.getCreatureExperienceSnapshot()).thenReturn(300L);
        when(assignment.getContributionScoreSnapshot()).thenReturn(60L);
        when(creature.getGroupSeason()).thenReturn(groupSeason);
        when(creature.getId()).thenReturn(51L);

        when(seasonQueryService.findCurrentSeasonForShareIfPresent())
                .thenReturn(Optional.of(season));
        when(seasonQueryService.findCurrentSeason()).thenReturn(season);
        when(userRepository.findByIdForUpdate(7L)).thenReturn(Optional.of(user));
        when(membershipRepository.findActiveWithGroupByUserId(7L))
                .thenReturn(Optional.of(membership));
        when(assignmentRepository.findWithSnapshotById(11L))
                .thenReturn(Optional.of(assignment));
        when(groupSeasonRepository.findForUpdateByGroupIdAndSeasonId(9L, 3L))
                .thenReturn(Optional.of(groupSeason));
    }
}
