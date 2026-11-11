package com.team.sharedcreature.payment.infrastructure;

import com.team.sharedcreature.payment.domain.RefundRequest;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

/**
 * RefundRequest 엔티티의 기본 영속성 연산을 제공합니다.
 */
public interface RefundRequestRepository extends JpaRepository<RefundRequest, Long> {
    List<RefundRequest> findByPurchaseIdOrderByRequestedAtAsc(Long purchaseId);

    Optional<RefundRequest> findFirstByPurchaseIdOrderByRequestedAtDesc(Long purchaseId);

    /**
     * 구매에 연결된 환불 요청들을 비관적 쓰기 잠금으로 조회합니다.
     *
     * 호출자는 구매와 보유 아이템을 순서대로 잠근 상태여야 합니다.
     *
     * @param purchaseId 구매 ID
     * @return 잠근 환불 요청 목록, 이력이 없으면 빈 목록
     */
    // 여러 환불 이력은 ID 오름차순으로 조회해 잠금 순서를 일관되게 합니다.
    // 환불 요청이 없으면 빈 목록을 반환하며, 오류가 아닙니다.
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            select refund
            from RefundRequest refund
            where refund.purchase.id = :purchaseId
            order by refund.id asc
            """)
    List<RefundRequest> findAllByPurchaseIdForUpdate(
            @Param("purchaseId") Long purchaseId
    );
}
