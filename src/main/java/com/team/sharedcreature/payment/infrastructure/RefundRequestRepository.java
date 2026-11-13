package com.team.sharedcreature.payment.infrastructure;

import com.team.sharedcreature.payment.domain.RefundRequest;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

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

    /**
     * 본인이 신청한 환불 요청을 상태 조건에 따라 페이지 조회합니다.
     *
     * @param userId 인증된 신청 사용자 ID
     * @param status 조회할 환불 상태, null이면 전체
     * @param pageable 페이지 번호와 크기
     * @return 본인의 환불 요청 페이지
     */
    @Query(
            value = """
                    SELECT refund
                      FROM RefundRequest refund
                     WHERE refund.requestedByUser.id = :userId
                       AND (:status IS NULL OR refund.status = :status)
                     ORDER BY refund.requestedAt DESC, refund.id DESC
                    """,
            countQuery = """
                    SELECT COUNT(refund)
                      FROM RefundRequest refund
                     WHERE refund.requestedByUser.id = :userId
                       AND (:status IS NULL OR refund.status = :status)
                    """
    )
    Page<RefundRequest> findMyRefunds(
            @Param("userId") Long userId,
            @Param("status") String status,
            Pageable pageable
    );

    /**
     * 관리자에게 전체 환불 요청을 상태 조건에 따라 페이지 제공합니다.
     *
     * @param status 조회할 환불 상태, null이면 전체
     * @param pageable 페이지 번호와 크기
     * @return 전체 환불 요청 페이지
     */
    @Query(
            value = """
                    SELECT refund
                    FROM RefundRequest refund
                    WHERE (:status IS NULL OR refund.status = :status)
                    ORDER BY refund.requestedAt DESC, refund.id DESC
                    """,
            countQuery = """
                    SELECT COUNT(refund)
                      FROM RefundRequest refund
                     WHERE (:status IS NULL OR refund.status = :status)
                    """
    )
    Page<RefundRequest> findAdminRefunds(
            @Param("status") String status,
            Pageable pageable
    );

    /**
     * 관리자 상세 응답에 필요한 구매 정보를 함께 조회합니다.
     * 상태 변경을 위한 잠금 조회와는 구분합니다.
     *
     * @param requestId 환불 요청 ID
     * @return 구매 정보를 포함한 환불 요청
     */
    @Query("""
            SELECT refund
            FROM RefundRequest refund
            JOIN FETCH refund.purchase
            WHERE refund.id = :requestId
            """)
    Optional<RefundRequest> findDetailById(
            @Param("requestId") Long requestId
    );

    /**
     * 환불 요청에 연결된 구매 ID만 조회합니다.
     * 구매부터 잠그기 위한 사전 조회이며 환불 요청은 잠그지 않습니다.
     *
     * @param requestId 환불 요청 ID
     * @return 연결된 구매 ID, 요청이 없으면 빈 Optional
     */
    @Query("""
            SELECT refund.purchase.id
            FROM RefundRequest refund
            WHERE refund.id = :requestId
            """)
    Optional<Long> findPurchaseIdByRequestId(
            @Param("requestId") Long requestId
    );
}
