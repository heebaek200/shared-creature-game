package com.team.sharedcreature.admin.integration;

import com.team.sharedcreature.admin.domain.AdminAuditActionType;
import com.team.sharedcreature.admin.domain.AdminAuditTargetType;
import com.team.sharedcreature.admin.domain.AdminAuditLog;
import com.team.sharedcreature.admin.domain.AdminAuditResult;
import com.team.sharedcreature.auth.domain.GlobalRole;
import com.team.sharedcreature.auth.domain.User;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/** 성공·실패 감사 결과와 작업 정보의 DB 왕복 저장을 검증합니다. */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
@EnabledIfEnvironmentVariable(named = "TEST_DB_USERNAME", matches = ".+")
class AdminAuditResultIntegrationTest {
    @Autowired private EntityManager em;

    /** Enum은 기존 문자열로 저장되고 다시 조회해도 작업 맥락이 유지됩니다. */
    @ParameterizedTest
    @CsvSource({
            "DISMISS_REPORT, USER_REPORT, SUCCEEDED",
            "REMOVE_REPORTED_MEMBER, USER_REPORT, FAILED",
            "TRANSFER_PRIMARY_AS_SUPER_ADMIN, GROUP, SUCCEEDED",
            "RESOLVE_REPORT, USER_REPORT, SUCCESS"
    })
    void preservesResultAndCommandContext(
            AdminAuditActionType actionType, AdminAuditTargetType targetType, AdminAuditResult result
    ) {
        Instant now = Instant.parse("2026-11-17T00:00:00Z");
        User actor = User.register(GlobalRole.SUPER_ADMIN, now);
        em.persist(actor);
        AdminAuditLog log = AdminAuditLog.create(
                actor, actionType, targetType, actor.getId(), null,
                result, "audit test", "{}", now);
        em.persist(log);
        em.flush();
        Long id = log.getId();
        em.clear();

        Object[] codes = (Object[]) em.createNativeQuery(
                        "SELECT action_type, target_type, result FROM admin_audit_logs WHERE id = :id")
                .setParameter("id", id).getSingleResult();
        assertThat(codes).containsExactly(actionType.name(), targetType.name(), result.name());
        AdminAuditLog saved = em.find(AdminAuditLog.class, id);
        assertThat(saved.getResult()).isEqualTo(result);
        assertThat(saved.getActorRoleSnapshot()).isEqualTo(GlobalRole.SUPER_ADMIN);
        assertThat(saved.getTargetId()).isEqualTo(actor.getId());
        assertThat(saved.getActionType()).isEqualTo(actionType);
        assertThat(saved.getTargetType()).isEqualTo(targetType);
        assertThat(saved.getReason()).isEqualTo("audit test");
        assertThat(saved.getCreatedAt()).isEqualTo(now);
    }
    /** 현재 권한이 변경되어도 감사 로그에는 기록 당시 권한과 DB 문자열이 남습니다. */
    @ParameterizedTest
    @EnumSource(GlobalRole.class)
    void preservesRoleSnapshotAfterActorRoleChanges(GlobalRole originalRole) {
        Instant now = Instant.parse("2026-11-17T00:00:00Z");
        User actor = User.register(originalRole, now);
        em.persist(actor);
        Long actorId = actor.getId();
        AdminAuditLog log = AdminAuditLog.create(
                actor, AdminAuditActionType.DISMISS_REPORT, AdminAuditTargetType.USER_REPORT, actorId, null,
                AdminAuditResult.SUCCEEDED, "snapshot test", "{}", now);
        em.persist(log);
        em.flush();
        Long logId = log.getId();
        GlobalRole changedRole = originalRole == GlobalRole.SUPER_ADMIN
                ? GlobalRole.USER : GlobalRole.SUPER_ADMIN;

        // Bulk UPDATE로 현재 권한만 변경하고 영속성 Context를 비운 뒤 다시 읽습니다.
        assertThat(em.createQuery("UPDATE User u SET u.globalRole = :role WHERE u.id = :id")
                .setParameter("role", changedRole).setParameter("id", actorId)
                .executeUpdate()).isEqualTo(1);
        em.clear();

        AdminAuditLog saved = em.find(AdminAuditLog.class, logId);
        assertThat(saved.getActorUser().getGlobalRole()).isEqualTo(changedRole);
        assertThat(saved.getActorRoleSnapshot()).isEqualTo(originalRole);
        assertThat(em.createNativeQuery(
                        "SELECT actor_role_snapshot FROM admin_audit_logs WHERE id = :id", String.class)
                .setParameter("id", logId).getSingleResult()).isEqualTo(originalRole.name());
        assertThat(saved.getResult()).isEqualTo(AdminAuditResult.SUCCEEDED);
        assertThat(saved.getCreatedAt()).isEqualTo(now);
    }
}
