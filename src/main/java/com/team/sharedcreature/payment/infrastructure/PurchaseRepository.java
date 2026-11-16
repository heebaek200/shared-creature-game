package com.team.sharedcreature.payment.infrastructure;

import com.team.sharedcreature.payment.domain.Purchase;
import com.team.sharedcreature.payment.domain.PurchaseStatus;
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

    /**
     * 사용자의 구매를 지정한 결제 상태로 페이지 조회합니다.
     *
     * @param userId 조회할 사용자 ID
     * @param status 구매 결제 상태
     * @param pageable 페이지 번호·크기·정렬 조건
     * @return 해당 사용자의 상태별 구매 페이지
     */
    Page<Purchase> findByUserIdAndStatus(
            Long userId,
            PurchaseStatus status,
            Pageable pageable
    );

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT purchase FROM Purchase purchase WHERE purchase.id = :purchaseId")
    Optional<Purchase> findByIdForUpdate(@Param("purchaseId") Long purchaseId);
}
