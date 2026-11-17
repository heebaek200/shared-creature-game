package com.team.sharedcreature.admin.application;

import com.team.sharedcreature.admin.domain.AdminAuditLog;
import com.team.sharedcreature.admin.domain.AdminAuditResult;
import com.team.sharedcreature.admin.infrastructure.AdminAuditLogRepository;
import com.team.sharedcreature.auth.domain.GlobalRole;
import com.team.sharedcreature.auth.domain.User;
import com.team.sharedcreature.auth.infrastructure.UserRepository;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

/** 실패 감사 기록의 결과와 기록 대상 권한을 검증합니다. */
class AdminAuditServiceTest {
    private static final Instant NOW = Instant.parse("2026-11-17T00:00:00Z");
    private final AdminAuditLogRepository logs = mock(AdminAuditLogRepository.class);
    private final UserRepository users = mock(UserRepository.class);
    private final AdminAuditService service = new AdminAuditService(
            logs, users, Clock.fixed(NOW, ZoneOffset.UTC));

    /** 실패 결과와 원래 작업 정보는 함께 기록되어야 합니다. */
    @Test
    void recordsFailureWithCommandContext() {
        User actor = User.register(GlobalRole.SUPER_ADMIN, NOW);
        when(users.findById(7L)).thenReturn(Optional.of(actor));

        service.recordFailure(7L, "SUSPEND_USER", "USER", 21L, "USER_NOT_FOUND");

        var captor = ArgumentCaptor.forClass(AdminAuditLog.class);
        verify(logs).save(captor.capture());
        AdminAuditLog log = captor.getValue();
        assertThat(log.getResult()).isEqualTo(AdminAuditResult.FAILED);
        assertThat(log.getActorUser()).isSameAs(actor);
        assertThat(log.getActorRoleSnapshot()).isEqualTo(GlobalRole.SUPER_ADMIN);
        assertThat(log.getActionType()).isEqualTo("SUSPEND_USER");
        assertThat(log.getTargetType()).isEqualTo("USER");
        assertThat(log.getTargetId()).isEqualTo(21L);
        assertThat(log.getReason()).isEqualTo("USER_NOT_FOUND");
        assertThat(log.getDetail()).isEqualTo("{}");
        assertThat(log.getCreatedAt()).isEqualTo(NOW);
    }

    /** 일반 사용자 요청은 관리자 실패 이력으로 기록하지 않습니다. */
    @Test
    void ordinaryUserDoesNotCreateAuditLog() {
        when(users.findById(7L)).thenReturn(Optional.of(User.register(GlobalRole.USER, NOW)));
        service.recordFailure(7L, "SUSPEND_USER", "USER", 21L, "FORBIDDEN");
        verifyNoInteractions(logs);
    }

    /** 존재하지 않는 행위자는 감사 로그의 필수 외래 키를 생성하지 않습니다. */
    @Test
    void missingActorDoesNotCreateAuditLog() {
        when(users.findById(7L)).thenReturn(Optional.empty());
        service.recordFailure(7L, "SUSPEND_USER", "USER", 21L, "FORBIDDEN");
        verifyNoInteractions(logs);
    }
}
