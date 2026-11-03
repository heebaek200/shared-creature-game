package com.team.sharedcreature.admin.infrastructure;

import com.team.sharedcreature.admin.domain.SchedulerExecution;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * SchedulerExecution 엔티티의 기본 영속성 연산을 제공합니다.
 */
public interface SchedulerExecutionRepository extends JpaRepository<SchedulerExecution, Long> {
}
