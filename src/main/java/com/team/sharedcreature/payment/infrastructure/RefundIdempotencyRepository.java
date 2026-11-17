package com.team.sharedcreature.payment.infrastructure;

import com.team.sharedcreature.admin.domain.ApiIdempotencyRecord;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Optional;
import java.util.List;

/**
 * 환불 승인·복구 명령의 멱등 레코드 연결과 결과 확정에 사용합니다.
 * 기존 공통 멱등 Repository와 다른 Operation은 변경하지 않습니다.
 */
@Transactional(propagation = Propagation.MANDATORY)
public interface RefundIdempotencyRepository
        extends Repository<ApiIdempotencyRecord, Long> {

    /**
     * 신규 처리권에 환불 요청 ID를 연결합니다.
     * PROCESSING 상태를 유지하며 아직 최종 응답은 저장하지 않습니다.
     *
     * @param recordId 신규 멱등 레코드 ID
     * @param requestId 처리할 환불 요청 ID
     * @param now 연결을 기록하는 서버 시각
     * @return 연결에 성공한 행 수
     */
    @Modifying(flushAutomatically = true)
    @Query("""
            UPDATE ApiIdempotencyRecord entry
            SET entry.resourceType = 'REFUND_REQUEST',
                entry.resourceId = :requestId,
                entry.updatedAt = :now
            WHERE entry.id = :recordId
            AND entry.operation IN ('approveRefund', 'reconcileRefund')
            AND entry.status = com.team.sharedcreature.admin.domain.ApiIdempotencyStatus.PROCESSING
            AND entry.resourceType IS NULL
            AND entry.resourceId IS NULL
            """)
    int bindProcessingRefund(
            @Param("recordId") Long recordId,
            @Param("requestId") Long requestId,
            @Param("now") Instant now
    );

    /**
     * 특정 환불 명령의 멱등 레코드를 잠금 조회합니다.
     * 이미 저장된 최초 응답을 덮어쓰지 않도록 상태를 재검증할 때 사용합니다.
     *
     * @param recordId 멱등 레코드 ID
     * @param requestId 연결된 환불 요청 ID
     * @return 잠근 환불 명령 멱등 레코드
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            SELECT entry
            FROM ApiIdempotencyRecord entry
            WHERE entry.id = :recordId
            AND entry.resourceType = 'REFUND_REQUEST'
            AND entry.resourceId = :requestId
            AND entry.operation IN ('approveRefund', 'reconcileRefund')
            """)
    Optional<ApiIdempotencyRecord> findForUpdateByIdAndRefundRequestId(
            @Param("recordId") Long recordId,
            @Param("requestId") Long requestId
    );

    /**
     * 환불 승인·수동 복구의 미완료 멱등 기록을 잠금 조회합니다.
     * 호출자는 구매·아이템·환불·PG 이력을 먼저 잠가야 합니다.
     *
     * @param requestId 복구 중인 환불 요청 ID
     * @return ID 오름차순의 미완료 멱등 기록
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
        SELECT entry
        FROM ApiIdempotencyRecord entry
        WHERE entry.resourceType = 'REFUND_REQUEST'
        AND entry.resourceId = :requestId
        AND entry.operation IN ('approveRefund', 'reconcileRefund')
        AND entry.status = com.team.sharedcreature.admin.domain.ApiIdempotencyStatus.PROCESSING
        ORDER BY entry.id ASC
        """)
    List<ApiIdempotencyRecord> findProcessingByRefundRequestIdForUpdate(
            @Param("requestId") Long requestId
    );
}
