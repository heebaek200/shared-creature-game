package com.team.sharedcreature.admin.infrastructure;

import com.team.sharedcreature.admin.domain.AdminAuditLog;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * AdminAuditLog 엔티티의 기본 영속성 연산을 제공합니다.
 */
public interface AdminAuditLogRepository extends JpaRepository<AdminAuditLog, Long> {
}
