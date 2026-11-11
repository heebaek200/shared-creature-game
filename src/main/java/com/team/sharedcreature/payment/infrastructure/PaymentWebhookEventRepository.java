package com.team.sharedcreature.payment.infrastructure;

import com.team.sharedcreature.payment.domain.PaymentWebhookEvent;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * PortOne Webhook의 접수·단건 잠금·복구 후보 조회를 제공합니다.
 * FAILED와 PENDING의 기준 시각을 서로 다른 컬럼으로 판정합니다.
 * 복구 여부는 단건 락 이후 도메인 메서드에서도 다시 확인합니다.
 */
public interface PaymentWebhookEventRepository extends JpaRepository<PaymentWebhookEvent, Long> {

    Optional<PaymentWebhookEvent> findByProviderAndProviderEventId(String provider, String providerEventId);

    boolean existsByProviderAndProviderEventId(String provider, String providerEventId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT event FROM PaymentWebhookEvent event WHERE event.id = :eventId")
    Optional<PaymentWebhookEvent> findByIdForUpdate(@Param("eventId") Long eventId);

    /**
     * 5분 이상 미처리 상태에 머문 결제 Webhook의 복구 후보를 조회합니다.
     * FAILED는 마지막 실패 시간(updatedAt), PENDING은 처리 시작 시각을 기준으로 합니다.
     * 취소 이벤트는 상위 서비스가 전달하는 eventTypes 목록에서 제외합니다.
     */
    @Query("""
            SELECT event
              FROM PaymentWebhookEvent event
             WHERE event.eventType IN :eventTypes
               AND ((event.processingStatus = 'FAILED' AND event.updatedAt <= :staleBefore)
                 OR (event.processingStatus = 'PENDING' AND event.processingStartedAt <= :staleBefore))
             ORDER BY event.processingStartedAt ASC, event.id ASC
            """)
    List<PaymentWebhookEvent> findRecoverable(
            @Param("eventTypes") Collection<String> eventTypes,
            @Param("staleBefore") Instant staleBefore,
            Pageable pageable
    );
}
