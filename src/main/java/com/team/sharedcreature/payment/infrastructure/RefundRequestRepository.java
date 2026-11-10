package com.team.sharedcreature.payment.infrastructure;

import com.team.sharedcreature.payment.domain.RefundRequest;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

/**
 * RefundRequest 엔티티의 기본 영속성 연산을 제공합니다.
 */
public interface RefundRequestRepository extends JpaRepository<RefundRequest, Long> {
    List<RefundRequest> findByPurchaseIdOrderByRequestedAtAsc(Long purchaseId);

    Optional<RefundRequest> findFirstByPurchaseIdOrderByRequestedAtDesc(Long purchaseId);
}
