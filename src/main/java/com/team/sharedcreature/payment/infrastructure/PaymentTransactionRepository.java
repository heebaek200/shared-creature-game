package com.team.sharedcreature.payment.infrastructure;

import com.team.sharedcreature.payment.domain.PaymentTransaction;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

/**
 * PaymentTransaction 엔티티의 기본 영속성 연산을 제공합니다.
 */
public interface PaymentTransactionRepository extends JpaRepository<PaymentTransaction, Long> {
    List<PaymentTransaction> findByPurchaseIdOrderByCreatedAtAsc(Long purchaseId);

    Optional<PaymentTransaction> findFirstByPurchaseIdAndTransactionTypeOrderByCreatedAtDesc(
            Long purchaseId,
            String transactionType
    );

    Optional<PaymentTransaction> findByProviderAndProviderTransactionId(
            String provider,
            String providerTransactionId
    );
}
