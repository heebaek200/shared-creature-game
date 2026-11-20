package com.team.sharedcreature.quest.api;

import com.team.sharedcreature.quest.domain.QuestPeriodType;
import com.team.sharedcreature.quest.domain.QuestAssignment;
import com.team.sharedcreature.quest.domain.QuestPeriod;
import com.team.sharedcreature.quest.domain.QuestTemplate;
import com.team.sharedcreature.season.domain.GroupSeasonStatus;

import com.team.sharedcreature._core.exception.BusinessException;
import com.team.sharedcreature._core.exception.ErrorCode;
import com.team.sharedcreature.auth.domain.User;
import com.team.sharedcreature.auth.infrastructure.UserRepository;
import com.team.sharedcreature.group.domain.Group;
import com.team.sharedcreature.group.domain.GroupMembership;
import com.team.sharedcreature.group.infrastructure.GroupMembershipRepository;
import com.team.sharedcreature.quest.domain.ServiceCheckIn;
import com.team.sharedcreature.quest.domain.UserActivityEvent;
import com.team.sharedcreature.quest.domain.UserActivityEventType;
import com.team.sharedcreature.quest.domain.UserActivitySourceType;
import com.team.sharedcreature.quest.infrastructure.QuestAssignmentRepository;
import com.team.sharedcreature.quest.infrastructure.QuestPeriodRepository;
import com.team.sharedcreature.quest.infrastructure.ServiceCheckInRepository;
import com.team.sharedcreature.quest.infrastructure.UserActivityEventRepository;
import com.team.sharedcreature.season.application.SeasonQueryService;
import com.team.sharedcreature.season.domain.GroupSeason;
import com.team.sharedcreature.season.domain.Season;
import com.team.sharedcreature.season.infrastructure.GroupSeasonRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CheckInServiceTest {

    @Mock private QuestCompletionService questCompletionService;
    @Mock private UserRepository userRepository;
    @Mock private GroupMembershipRepository membershipRepository;
    @Mock private ServiceCheckInRepository serviceCheckInRepository;
    @Mock private UserActivityEventRepository activityEventRepository;
    @Mock private SeasonQueryService seasonQueryService;
    @Mock private GroupSeasonRepository groupSeasonRepository;
    @Mock private QuestPeriodRepository periodRepository;
    @Mock private QuestAssignmentRepository assignmentRepository;

    @Test
    void 같은_KST_날짜의_두번째_체크인은_409로_거절한다() {
        Instant now = Instant.parse("2026-11-05T01:00:00Z");
        CheckInService service = givenService(now);
        givenCheckInContext(now);
        when(serviceCheckInRepository.existsByUserIdAndCheckInDate(
                1L,
                LocalDate.of(2026, 11, 5)
        )).thenReturn(true);

        assertThatThrownBy(() -> service.createCheckIn(1L))
                .isInstanceOfSatisfying(BusinessException.class, exception -> {
                    assertThat(exception.status()).isEqualTo(HttpStatus.CONFLICT);
                    assertThat(exception.code()).isEqualTo("CONCURRENT_MODIFICATION");
                });
    }

    @Test
    void KST_오전_5시_체크인은_기상_체크인으로_저장한다() {
        Instant now = Instant.parse("2026-11-04T20:00:00Z");
        CheckInService service = givenService(now);
        givenSuccessfulCheckIn(now);

        CheckInResponse response = service.createCheckIn(1L);

        InOrder lockOrder = inOrder(
                seasonQueryService,
                userRepository,
                membershipRepository,
                groupSeasonRepository
        );
        lockOrder.verify(seasonQueryService).findCurrentQuestSeasonForShare();
        lockOrder.verify(userRepository).findByIdForUpdate(1L);
        lockOrder.verify(membershipRepository).findActiveWithGroupByUserId(1L);
        lockOrder.verify(groupSeasonRepository).findForUpdateByGroupIdAndSeasonId(5L, 9L);
        verify(seasonQueryService, times(1)).findCurrentQuestSeasonForShare();

        assertThat(response.getCheckInDate()).isEqualTo(LocalDate.of(2026, 11, 5));
        assertThat(response.isMorning()).isTrue();
        assertThat(response.getCompletedQuestIds()).isEmpty();

        ArgumentCaptor<UserActivityEvent> eventCaptor = ArgumentCaptor.forClass(UserActivityEvent.class);
        verify(activityEventRepository).save(eventCaptor.capture());
        UserActivityEvent event = eventCaptor.getValue();
        assertThat(event.getEventType()).isEqualTo(UserActivityEventType.CHECK_IN);
        assertThat(event.getSourceType()).isEqualTo(UserActivitySourceType.SERVICE_CHECK_IN);
        assertThat(event.getDeduplicationKey()).isEqualTo("check_in:1:2026-11-05");
    }

    @Test
    void KST_오전_9시_체크인은_기상_체크인이_아니다() {
        Instant now = Instant.parse("2026-11-05T00:00:00Z");
        CheckInService service = givenService(now);
        givenSuccessfulCheckIn(now);

        CheckInResponse response = service.createCheckIn(1L);

        assertThat(response.getCheckInDate()).isEqualTo(LocalDate.of(2026, 11, 5));
        assertThat(response.isMorning()).isFalse();
        assertThat(response.getCompletedQuestIds()).isEmpty();
    }

    @Test
    void 부화전_그룹도_체크인을_기록하고_집계_근거를_남긴다() {
        Instant now = Instant.parse("2026-11-05T00:00:00Z");
        CheckInService service = givenService(now);
        givenSuccessfulCheckIn(now, GroupSeasonStatus.PREPARING);

        CheckInResponse response = service.createCheckIn(1L);

        assertThat(response.getCheckInDate()).isEqualTo(LocalDate.of(2026, 11, 5));
        verify(activityEventRepository).save(any(UserActivityEvent.class));
        verify(serviceCheckInRepository).save(any(ServiceCheckIn.class));
    }

    @Test
    void 부화전_기상_체크인은_SERVER_EVENT와_주간_AGGREGATE를_자동_완료한다() {
        Instant now = Instant.parse("2026-11-04T20:00:00Z");
        CheckInService service = givenService(now);
        givenSuccessfulCheckIn(now, GroupSeasonStatus.PREPARING);

        QuestPeriod dailyPeriod = mock(QuestPeriod.class);
        QuestPeriod weeklyPeriod = mock(QuestPeriod.class);
        QuestAssignment morningAssignment = mock(QuestAssignment.class);
        QuestAssignment aggregateAssignment = mock(QuestAssignment.class);
        QuestTemplate morningTemplate = mock(QuestTemplate.class);
        QuestTemplate aggregateTemplate = mock(QuestTemplate.class);
        when(dailyPeriod.getId()).thenReturn(101L);
        when(weeklyPeriod.getId()).thenReturn(102L);
        when(morningAssignment.getId()).thenReturn(201L);
        when(aggregateAssignment.getId()).thenReturn(202L);
        when(morningAssignment.getQuestTemplate()).thenReturn(morningTemplate);
        when(aggregateAssignment.getQuestTemplate()).thenReturn(aggregateTemplate);
        when(morningTemplate.getCode()).thenReturn("D-M-01");
        when(aggregateTemplate.getCode()).thenReturn("W-L-01");
        when(periodRepository.findCurrentBySeasonAndType(9L, QuestPeriodType.DAILY, now))
                .thenReturn(Optional.of(dailyPeriod));
        when(periodRepository.findCurrentBySeasonAndType(9L, QuestPeriodType.WEEKLY, now))
                .thenReturn(Optional.of(weeklyPeriod));
        when(assignmentRepository.findAllWithSnapshotByPeriodId(101L))
                .thenReturn(List.of(morningAssignment));
        when(assignmentRepository.findAllWithSnapshotByPeriodId(102L))
                .thenReturn(List.of(aggregateAssignment));

        service.createCheckIn(1L);

        verify(questCompletionService).completeServerEventAssignment(1L, 201L);
        verify(questCompletionService).completeCheckInAggregateIfEligible(1L, 202L);
    }

    @Test
    void 그룹_시즌_정보가_없으면_RESOURCE_NOT_FOUND로_거절한다() {
        Instant now = Instant.parse("2026-11-05T01:00:00Z");
        CheckInService service = givenService(now);
        givenCheckInContext(now);
        when(groupSeasonRepository.findForUpdateByGroupIdAndSeasonId(5L, 9L))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.createCheckIn(1L))
                .isInstanceOfSatisfying(BusinessException.class, exception ->
                        assertThat(exception.errorCode()).isEqualTo(ErrorCode.RESOURCE_NOT_FOUND));

        verifyNoInteractions(activityEventRepository);
    }

    @Test
    void 종료된_그룹_시즌은_GROUP_SEASON_ENDED로_거절한다() {
        Instant now = Instant.parse("2026-11-05T01:00:00Z");
        CheckInService service = givenService(now);
        givenCheckInContext(now, GroupSeasonStatus.ENDED);

        assertThatThrownBy(() -> service.createCheckIn(1L))
                .isInstanceOfSatisfying(BusinessException.class, exception ->
                        assertThat(exception.errorCode()).isEqualTo(ErrorCode.GROUP_SEASON_ENDED));

        verifyNoInteractions(activityEventRepository);
    }

    private CheckInService givenService(Instant now) {
        return new CheckInService(
                questCompletionService,
                userRepository,
                membershipRepository,
                serviceCheckInRepository,
                activityEventRepository,
                seasonQueryService,
                groupSeasonRepository,
                Clock.fixed(now, ZoneOffset.UTC),
                periodRepository,
                assignmentRepository
        );
    }

    private void givenCheckInContext(Instant now) {
        givenCheckInContext(now, GroupSeasonStatus.ACTIVE);
    }

    private void givenCheckInContext(Instant now, GroupSeasonStatus status) {
        User user = mock(User.class);
        Group group = mock(Group.class);
        GroupMembership membership = mock(GroupMembership.class);
        Season season = mock(Season.class);
        GroupSeason groupSeason = mock(GroupSeason.class);

        when(userRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(user));
        when(membershipRepository.findActiveWithGroupByUserId(1L))
                .thenReturn(Optional.of(membership));
        when(membership.getGroup()).thenReturn(group);
        when(group.getId()).thenReturn(5L);
        when(seasonQueryService.findCurrentQuestSeasonForShare()).thenReturn(season);
        when(season.getId()).thenReturn(9L);
        when(groupSeasonRepository.findForUpdateByGroupIdAndSeasonId(5L, 9L))
                .thenReturn(Optional.of(groupSeason));
        lenient().when(groupSeason.getStatus()).thenReturn(status);
        lenient().when(serviceCheckInRepository.existsByUserIdAndCheckInDate(
                1L,
                now.atZone(ZoneId.of("Asia/Seoul")).toLocalDate()
        )).thenReturn(false);
    }

    private void givenSuccessfulCheckIn(Instant now) {
        givenSuccessfulCheckIn(now, GroupSeasonStatus.ACTIVE);
    }

    private void givenSuccessfulCheckIn(Instant now, GroupSeasonStatus status) {
        givenCheckInContext(now, status);

        UserActivityEvent activityEvent = mock(UserActivityEvent.class);
        when(activityEventRepository.save(any(UserActivityEvent.class))).thenReturn(activityEvent);
        when(serviceCheckInRepository.save(any(ServiceCheckIn.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        Season season = mock(Season.class);
        when(seasonQueryService.findCurrentSeason()).thenReturn(season);
        when(season.getId()).thenReturn(9L);
        when(periodRepository.findCurrentBySeasonAndType(9L, QuestPeriodType.WEEKLY, now))
                .thenReturn(Optional.empty());

        if (now.atZone(ZoneId.of("Asia/Seoul")).toLocalTime().isBefore(java.time.LocalTime.of(9, 0))
                && !now.atZone(ZoneId.of("Asia/Seoul")).toLocalTime().isBefore(java.time.LocalTime.of(5, 0))) {
            when(periodRepository.findCurrentBySeasonAndType(9L, QuestPeriodType.DAILY, now))
                    .thenReturn(Optional.empty());
        }

        when(questCompletionService.findCurrentAssignments(QuestPeriodType.DAILY))
                .thenReturn(new ArrayList<>());
        when(questCompletionService.findCurrentAssignments(QuestPeriodType.WEEKLY))
                .thenReturn(new ArrayList<>());
        when(questCompletionService.findCompletionsByAssignmentId(eq(1L), anyList()))
                .thenReturn(new HashMap<>());
    }
}
