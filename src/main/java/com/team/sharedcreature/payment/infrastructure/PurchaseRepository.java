package com.team.sharedcreature.payment.infrastructure;

import com.team.sharedcreature.payment.domain.Purchase;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Purchase 엔티티의 기본 영속성 연산을 제공합니다.
 */
public interface PurchaseRepository extends JpaRepository<Purchase, Long> {
}
