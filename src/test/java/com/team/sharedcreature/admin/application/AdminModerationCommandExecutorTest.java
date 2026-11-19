package com.team.sharedcreature.admin.application;

import com.team.sharedcreature._core.exception.BusinessException;
import com.team.sharedcreature.admin.infrastructure.AdminAuditLogRepository;
import com.team.sharedcreature.auth.domain.GlobalRole;
import com.team.sharedcreature.auth.domain.User;
import com.team.sharedcreature.auth.domain.UserStatus;
import com.team.sharedcreature.auth.infrastructure.UserRepository;
import com.team.sharedcreature.group.domain.Group;
import com.team.sharedcreature.group.domain.UserReport;
import com.team.sharedcreature.group.domain.UserReportCategory;
import com.team.sharedcreature.group.domain.UserReportStatus;
import com.team.sharedcreature.group.infrastructure.GroupJoinRestrictionRepository;
import com.team.sharedcreature.group.infrastructure.GroupMembershipRepository;
import com.team.sharedcreature.group.infrastructure.GroupRepository;
import com.team.sharedcreature.group.infrastructure.ModerationActionRepository;
import com.team.sharedcreature.group.infrastructure.UserReportRepository;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AdminModerationCommandExecutorTest {

    @Test
    void 신고_대상이_이미_그룹을_떠났으면_추가_강퇴_없이_처리한다() {
        Fixture fixture = new Fixture(false);

        fixture.executor.resolveInactive(99L, 30L, "이미 탈퇴 확인");

        assertThat(fixture.report.getStatus()).isEqualTo(UserReportStatus.RESOLVED);
        assertThat(fixture.report.getResolution()).isEqualTo("이미 탈퇴 확인");
        verify(fixture.auditLogs).save(any());
        verify(fixture.memberships).findActiveMembershipId(10L, 2L);
    }

    @Test
    void 신고_대상이_아직_활성_회원이면_처리_완료를_거부한다() {
        Fixture fixture = new Fixture(true);

        assertThatThrownBy(() -> fixture.executor.resolveInactive(99L, 30L, "처리"))
                .isInstanceOf(BusinessException.class);
        assertThat(fixture.report.getStatus()).isEqualTo(UserReportStatus.RECEIVED);
        verify(fixture.auditLogs, never()).save(any());
    }

    private static final class Fixture {
        private final GroupMembershipRepository memberships = mock(GroupMembershipRepository.class);
        private final AdminAuditLogRepository auditLogs = mock(AdminAuditLogRepository.class);
        private final UserReport report;
        private final AdminModerationCommandExecutor executor;

        private Fixture(boolean activeTarget) {
            UserReportRepository reports = mock(UserReportRepository.class);
            UserRepository users = mock(UserRepository.class);
            Group group = mock(Group.class);
            User reporter = mock(User.class);
            User target = mock(User.class);
            User actor = mock(User.class);
            when(group.getId()).thenReturn(10L);
            when(target.getId()).thenReturn(2L);
            when(actor.getStatus()).thenReturn(UserStatus.ACTIVE);
            when(actor.getGlobalRole()).thenReturn(GlobalRole.SUPER_ADMIN);
            report = UserReport.receive(reporter, target, group, UserReportCategory.OTHER,
                    "신고", Instant.parse("2026-11-18T00:00:00Z"));
            when(reports.findByIdForUpdate(30L)).thenReturn(Optional.of(report));
            when(users.findByIdForUpdate(99L)).thenReturn(Optional.of(actor));
            when(memberships.findActiveMembershipId(10L, 2L)).thenReturn(
                    activeTarget ? Optional.of(200L) : Optional.empty());
            executor = new AdminModerationCommandExecutor(
                    reports,
                    users,
                    mock(GroupRepository.class),
                    memberships,
                    mock(GroupJoinRestrictionRepository.class),
                    mock(ModerationActionRepository.class),
                    auditLogs,
                    Clock.fixed(Instant.parse("2026-11-19T00:00:00Z"), ZoneOffset.UTC));
        }
    }
}
