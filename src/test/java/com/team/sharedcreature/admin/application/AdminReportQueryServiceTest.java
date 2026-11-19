package com.team.sharedcreature.admin.application;

import com.team.sharedcreature.auth.domain.User;
import com.team.sharedcreature.auth.domain.UserProfile;
import com.team.sharedcreature.auth.infrastructure.UserProfileRepository;
import com.team.sharedcreature.group.domain.Group;
import com.team.sharedcreature.group.domain.UserReport;
import com.team.sharedcreature.group.infrastructure.GroupMembershipRepository;
import com.team.sharedcreature.group.infrastructure.UserReportChatEvidenceRepository;
import com.team.sharedcreature.group.infrastructure.UserReportRepository;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AdminReportQueryServiceTest {

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void 신고_상세에_피신고자의_현재_활성_멤버십을_표시한다(boolean active) {
        UserReportRepository reports = mock(UserReportRepository.class);
        UserProfileRepository profiles = mock(UserProfileRepository.class);
        UserReportChatEvidenceRepository evidence = mock(UserReportChatEvidenceRepository.class);
        GroupMembershipRepository memberships = mock(GroupMembershipRepository.class);
        UserReport report = mock(UserReport.class);
        Group group = mock(Group.class);
        User reporter = mock(User.class);
        User reported = mock(User.class);
        UserProfile reporterProfile = mock(UserProfile.class);
        UserProfile reportedProfile = mock(UserProfile.class);
        when(report.getId()).thenReturn(30L);
        when(report.getGroup()).thenReturn(group);
        when(group.getId()).thenReturn(10L);
        when(report.getReporterUser()).thenReturn(reporter);
        when(report.getReportedUser()).thenReturn(reported);
        when(reporter.getId()).thenReturn(1L);
        when(reported.getId()).thenReturn(2L);
        when(profiles.findByUserId(1L)).thenReturn(Optional.of(reporterProfile));
        when(profiles.findByUserId(2L)).thenReturn(Optional.of(reportedProfile));
        when(reporterProfile.getNickname()).thenReturn("신고자");
        when(reportedProfile.getNickname()).thenReturn("피신고자");
        when(evidence.findByReportId(30L)).thenReturn(Optional.empty());
        when(memberships.findActiveMembershipId(10L, 2L)).thenReturn(
                active ? Optional.of(200L) : Optional.empty());
        AdminReportQueryService service = new AdminReportQueryService(
                reports, profiles, evidence, memberships);

        assertThat(service.toResponse(report).reportedUserActive()).isEqualTo(active);
    }
}
