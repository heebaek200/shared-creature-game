package com.team.sharedcreature.group.application;

import com.team.sharedcreature._core.exception.BusinessException;
import com.team.sharedcreature.auth.domain.User;
import com.team.sharedcreature.group.domain.Group;
import com.team.sharedcreature.group.domain.GroupMembership;
import com.team.sharedcreature.group.domain.UserReportCategory;
import com.team.sharedcreature.group.domain.UserReportStatus;
import com.team.sharedcreature.group.infrastructure.GroupMembershipRepository;
import com.team.sharedcreature.group.infrastructure.UserReportRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import tools.jackson.databind.ObjectMapper;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class UserReportServiceTest {

    @ParameterizedTest
    @EnumSource(UserReportCategory.class)
    void 같은_그룹의_활성_회원_신고를_RECEIVED로_접수한다(UserReportCategory category) {
        GroupMembershipRepository membershipRepository = mock(GroupMembershipRepository.class);
        UserReportRepository reportRepository = mock(UserReportRepository.class);
        Group group = mock(Group.class);
        User reporter = mock(User.class);
        User reported = mock(User.class);
        GroupMembership reporterMembership = membership(1L, reporter, group);
        GroupMembership reportedMembership = membership(2L, reported, group);
        when(group.getId()).thenReturn(10L);
        when(reporter.getId()).thenReturn(1L);
        when(reported.getId()).thenReturn(2L);
        when(membershipRepository.findActiveWithGroupByUserId(1L)).thenReturn(Optional.of(reporterMembership));
        when(membershipRepository.findActiveWithGroupByUserId(2L)).thenReturn(Optional.of(reportedMembership));
        when(reportRepository.save(org.mockito.ArgumentMatchers.any())).thenAnswer(invocation -> invocation.getArgument(0));
        UserReportService service = new UserReportService(
                membershipRepository,
                reportRepository,
                Clock.fixed(Instant.parse("2026-11-10T00:00:00Z"), ZoneOffset.UTC)
        );

        var response = service.create(1L, 2L, category, " 반복 광고 ");

        assertThat(response.status()).isEqualTo(UserReportStatus.RECEIVED);
        assertThat(response.category()).isEqualTo(category);
        assertThat(new ObjectMapper().writeValueAsString(response))
                .contains("\"category\":\"" + category.name() + "\"", "\"status\":\"RECEIVED\"");
        assertThat(response.content()).isEqualTo("반복 광고");
        assertThat(response.groupId()).isEqualTo(10L);
        verify(reportRepository).save(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void 다른_그룹_회원은_신고할_수_없다() {
        GroupMembershipRepository membershipRepository = mock(GroupMembershipRepository.class);
        UserReportRepository reportRepository = mock(UserReportRepository.class);
        Group first = mock(Group.class);
        Group second = mock(Group.class);
        User firstUser = mock(User.class);
        User secondUser = mock(User.class);
        GroupMembership firstMembership = membership(1L, firstUser, first);
        GroupMembership secondMembership = membership(2L, secondUser, second);
        when(first.getId()).thenReturn(10L);
        when(second.getId()).thenReturn(20L);
        when(membershipRepository.findActiveWithGroupByUserId(1L))
                .thenReturn(Optional.of(firstMembership));
        when(membershipRepository.findActiveWithGroupByUserId(2L))
                .thenReturn(Optional.of(secondMembership));
        UserReportService service = new UserReportService(
                membershipRepository, reportRepository, Clock.systemUTC()
        );

        assertThatThrownBy(() -> service.create(
                1L, 2L, UserReportCategory.OTHER, "내용"
        )).isInstanceOf(BusinessException.class);
    }

    private GroupMembership membership(Long id, User user, Group group) {
        GroupMembership membership = mock(GroupMembership.class);
        when(membership.getId()).thenReturn(id);
        when(membership.getUser()).thenReturn(user);
        when(membership.getGroup()).thenReturn(group);
        return membership;
    }
}
