package com.team.sharedcreature.quest.api;

import com.team.sharedcreature._core.exception.BusinessException;
import com.team.sharedcreature.auth.domain.User;
import com.team.sharedcreature.auth.infrastructure.UserRepository;
import com.team.sharedcreature.group.domain.Group;
import com.team.sharedcreature.group.domain.GroupMembership;
import com.team.sharedcreature.group.infrastructure.GroupMembershipRepository;
import com.team.sharedcreature.quest.domain.ServiceCheckIn;
import com.team.sharedcreature.quest.domain.UserActivityEvent;
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
import static org.mockito.Mockito.mock;
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

        assertThat(response.getCheckInDate()).isEqualTo(LocalDate.of(2026, 11, 5));
        assertThat(response.isMorning()).isTrue();
        assertThat(response.getCompletedQuestIds()).isEmpty();
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
        when(seasonQueryService.findCurrentSeasonForShare()).thenReturn(season);
        when(season.getId()).thenReturn(9L);
        when(groupSeasonRepository.findForUpdateByGroupIdAndSeasonId(5L, 9L))
                .thenReturn(Optional.of(groupSeason));
        when(groupSeason.getStatus()).thenReturn("ACTIVE");
        when(serviceCheckInRepository.existsByUserIdAndCheckInDate(
                1L,
                now.atZone(ZoneId.of("Asia/Seoul")).toLocalDate()
        )).thenReturn(false);
    }

    private void givenSuccessfulCheckIn(Instant now) {
        givenCheckInContext(now);

        UserActivityEvent activityEvent = mock(UserActivityEvent.class);
        when(activityEventRepository.save(any(UserActivityEvent.class))).thenReturn(activityEvent);
        when(serviceCheckInRepository.save(any(ServiceCheckIn.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        Season season = mock(Season.class);
        when(seasonQueryService.findCurrentSeason()).thenReturn(season);
        when(season.getId()).thenReturn(9L);
        when(periodRepository.findCurrentBySeasonAndType(9L, "WEEKLY", now))
                .thenReturn(Optional.empty());

        if (now.atZone(ZoneId.of("Asia/Seoul")).toLocalTime().isBefore(java.time.LocalTime.of(9, 0))
                && !now.atZone(ZoneId.of("Asia/Seoul")).toLocalTime().isBefore(java.time.LocalTime.of(5, 0))) {
            when(periodRepository.findCurrentBySeasonAndType(9L, "DAILY", now))
                    .thenReturn(Optional.empty());
        }

        when(questCompletionService.findCurrentAssignments("DAILY"))
                .thenReturn(new ArrayList<>());
        when(questCompletionService.findCurrentAssignments("WEEKLY"))
                .thenReturn(new ArrayList<>());
        when(questCompletionService.findCompletionsByAssignmentId(eq(1L), anyList()))
                .thenReturn(new HashMap<>());
    }
}
