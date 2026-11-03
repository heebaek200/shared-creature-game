package com.team.sharedcreature.payment.infrastructure;

import com.team.sharedcreature.payment.domain.PgApiAttempt;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * PgApiAttempt 엔티티의 기본 영속성 연산을 제공합니다.
 */
public interface PgApiAttemptRepository extends JpaRepository<PgApiAttempt, Long> {
}
