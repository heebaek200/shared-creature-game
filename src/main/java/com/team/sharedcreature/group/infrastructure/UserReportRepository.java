package com.team.sharedcreature.group.infrastructure;

import com.team.sharedcreature.group.domain.UserReport;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * UserReport 엔티티의 기본 영속성 연산을 제공합니다.
 */
public interface UserReportRepository extends JpaRepository<UserReport, Long> {
}
