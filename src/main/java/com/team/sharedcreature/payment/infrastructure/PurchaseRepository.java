package com.team.sharedcreature.payment.infrastructure;

import com.team.sharedcreature.payment.domain.Purchase;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import jakarta.persistence.LockModeType;
import java.util.Optional;

/**
 * Purchase 엔티티의 기본 영속성 연산을 제공합니다.
 */
public interface PurchaseRepository extends JpaRepository<Purchase, Long> {
    Optional<Purchase> findByOrderNumber(String orderNumber);

    Optional<Purchase> findByIdAndUserId(Long id, Long userId);

    Page<Purchase> findByUserId(Long userId, Pageable pageable);

    Page<Purchase> findByUserIdAndStatus(Long userId, String status, Pageable pageable);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT purchase FROM Purchase purchase WHERE purchase.id = :purchaseId")
    Optional<Purchase> findByIdForUpdate(@Param("purchaseId") Long purchaseId);
}
