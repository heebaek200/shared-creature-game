package com.team.sharedcreature.payment.infrastructure;

import com.team.sharedcreature.payment.domain.PaymentWebhookEvent;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * PaymentWebhookEvent 엔티티의 기본 영속성 연산을 제공합니다.
 */
public interface PaymentWebhookEventRepository extends JpaRepository<PaymentWebhookEvent, Long> {
    Optional<PaymentWebhookEvent> findByProviderAndProviderEventId(String provider, String providerEventId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT event FROM PaymentWebhookEvent event WHERE event.id = :eventId")
    Optional<PaymentWebhookEvent> findByIdForUpdate(@Param("eventId") Long eventId);

    @Query("""
            SELECT event
              FROM PaymentWebhookEvent event
             WHERE event.eventType IN :eventTypes
               AND (event.processingStatus = 'FAILED'
                 OR (event.processingStatus = 'PENDING' AND event.processingStartedAt <= :staleBefore))
             ORDER BY event.processingStartedAt ASC, event.id ASC
            """)
    List<PaymentWebhookEvent> findRecoverable(
            @Param("eventTypes") Collection<String> eventTypes,
            @Param("staleBefore") Instant staleBefore,
            Pageable pageable
    );
}
