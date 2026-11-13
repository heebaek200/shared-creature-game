package com.team.sharedcreature.payment.infrastructure;

import com.team.sharedcreature.payment.domain.PgApiAttempt;
import org.springframework.data.jpa.repository.JpaRepository;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

/**
 * PgApiAttempt 엔티티의 기본 영속성 연산을 제공합니다.
 */
public interface PgApiAttemptRepository extends JpaRepository<PgApiAttempt, Long> {

    /**
     * 특정 환불에 속한 PG 시도 이력을 잠금 조회합니다.
     * 호출자는 구매·보유 아이템·환불 요청을 먼저 잠근 상태여야 합니다.
     *
     * @param attemptId PG 시도 이력 ID
     * @param requestId 해당 이력이 속해야 하는 환불 요청 ID
     * @return 잠근 PG 시도 이력, 없거나 다른 환불 이력이면 빈 Optional
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            SELECT attempt
            FROM PgApiAttempt attempt
            WHERE attempt.id = :attemptId
            AND attempt.refundRequest.id = :requestId
            """)
    Optional<PgApiAttempt> findByIdAndRefundRequestIdForUpdate(
            @Param("attemptId") Long attemptId,
            @Param("requestId") Long requestId
    );

    /**
     * 환불의 취소 호출 이력을 정해진 순서로 잠금 조회합니다.
     * 상태 조회 이력은 제외하고 실제 취소 호출 이력만 조회합니다.
     *
     * @param requestId 환불 요청 ID
     * @return 시도 번호와 ID 오름차순의 취소 호출 이력
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            SELECT attempt
            FROM PgApiAttempt attempt
            WHERE attempt.refundRequest.id = :requestId
            AND attempt.operation = 'CANCELLATION'
            ORDER BY attempt.attemptNumber ASC, attempt.id ASC
            """)
    List<PgApiAttempt> findCancellationAttemptsForUpdate(
            @Param("requestId") Long requestId
    );
}
