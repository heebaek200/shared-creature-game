package com.team.sharedcreature.admin.domain;

import com.team.sharedcreature.admin.domain.AdminAuditActionType;
import com.team.sharedcreature.admin.domain.AdminAuditTargetType;
import com.team.sharedcreature.auth.domain.GlobalRole;
import com.team.sharedcreature.auth.domain.User;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

/** 감사 로그는 현재 권한을 참조하지 않고 생성 당시의 권한을 보관합니다. */
class AdminAuditLogTest {
    /** 원본 사용자의 권한이 바뀌어도 기록 당시 권한은 유지됩니다. */
    @ParameterizedTest
    @EnumSource(GlobalRole.class)
    void capturesRoleAtCreation(GlobalRole originalRole) {
        User actor = mock(User.class);
        when(actor.getGlobalRole()).thenReturn(originalRole);
        Instant now = Instant.parse("2026-11-17T00:00:00Z");
        AdminAuditLog log = AdminAuditLog.create(
                actor, AdminAuditActionType.DISMISS_REPORT, AdminAuditTargetType.USER_REPORT, 21L, null,
                AdminAuditResult.SUCCEEDED, "snapshot test", "{}", now);

        GlobalRole changedRole = originalRole == GlobalRole.SUPER_ADMIN
                ? GlobalRole.USER : GlobalRole.SUPER_ADMIN;
        when(actor.getGlobalRole()).thenReturn(changedRole);

        assertThat(log.getActorUser().getGlobalRole()).isEqualTo(changedRole);
        assertThat(log.getActorRoleSnapshot()).isEqualTo(originalRole);
        assertThat(log.getResult()).isEqualTo(AdminAuditResult.SUCCEEDED);
        assertThat(log.getCreatedAt()).isEqualTo(now);
    }
}
