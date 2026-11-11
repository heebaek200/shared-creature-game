package com.team.sharedcreature.payment.integration;

import com.team.sharedcreature.payment.domain.PaymentWebhookEvent;
import com.team.sharedcreature.payment.infrastructure.PaymentWebhookEventRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.ActiveProfiles;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * #21/#24 Webhook 복구의 5분 대상 선정과 행 잠금 이후 판정 규칙을 확인합니다.
 * MySQL JPA 쿼리와 PaymentWebhookEvent.isRecoverable()이 같은 결과를 내야 합니다.
 * 실제 PortOne 요청이나 기존 결제 원장에 대한 상태 변경은 수행하지 않습니다.
 */
@SpringBootTest
@ActiveProfiles("test")
@EnabledIfEnvironmentVariable(named = "TEST_DB_USERNAME", matches = ".+")
class PaymentWebhookRecoveryEligibilityMySqlIntegrationTest {

    private static final Instant NOW = Instant.parse("2026-11-11T01:30:00Z");
    private static final Instant CUTOFF = NOW.minus(Duration.ofMinutes(5));
    private static final Set<String> PAYMENT_TYPES = Set.of(
            "Transaction.Ready", "Transaction.Paid", "Transaction.Failed", "Transaction.PayPending");

    @Autowired private PaymentWebhookEventRepository repository;
    private final List<Long> createdIds = new ArrayList<>();

    /**
     * 테스트가 추가한 Webhook 행만 삭제합니다.
     * 프로젝트의 실제 결제 및 환불 원장은 건드리지 않습니다.
     * 여러 테스트의 실행 순서에 영향을 주지 않도록 각 메서드 종료 시 처리합니다.
     */
    @AfterEach
    void cleanUp() {
        // 저장 성공한 Fixture의 ID만 삭제하고 다른 Webhook 이력은 유지합니다.
        if (!createdIds.isEmpty()) {
            repository.deleteAllByIdInBatch(createdIds);
            createdIds.clear();
        }
    }

    /**
     * 마지막 실패가 4분 전이라면 오래전에 접수됐어도 복구하지 않습니다.
     * FAILED 대상은 최초 processingStartedAt이 아니라 updatedAt을 사용해야 합니다.
     * 쿼리와 잠금 후 도메인 재검증의 결과를 함께 확인합니다.
     */
    @Test
    void recentFailedWebhookWaitsFiveMinutesSinceFailure() {
        PaymentWebhookEvent event = newEvent("Transaction.Paid", NOW.minus(Duration.ofHours(1)));
        event.fail("PAYMENT_PENDING", NOW.minus(Duration.ofMinutes(4)));
        repository.saveAndFlush(event);

        // 최초 접수는 오래됐지만 마지막 실패가 최근이므로 재시도하지 않습니다.
        assertFalse(event.isRecoverable(CUTOFF));
        assertFalse(recoverableIds().contains(event.getId()));
    }

    /**
     * 마지막 실패가 정확히 5분 전이면 복구 대상에 포함합니다.
     * 계약상 '5분 이상'이므로 경계는 포함되어야 합니다.
     * 조회와 도메인 판정은 동일한 UTC Instant를 사용합니다.
     */
    @Test
    void failedWebhookAtExactFiveMinutesIsRecoverable() {
        PaymentWebhookEvent event = newEvent("Transaction.Paid", NOW.minus(Duration.ofHours(1)));
        event.fail("PURCHASE_NOT_FOUND", CUTOFF);
        repository.saveAndFlush(event);

        // 마지막 실패 시간과 cutoff가 정확히 일치합니다.
        assertTrue(event.isRecoverable(CUTOFF));
        assertTrue(recoverableIds().contains(event.getId()));
    }

    /**
     * PENDING은 최초 또는 마지막 처리 시작 시각부터 5분을 계산합니다.
     * 4분 59초 미경과 건과 5분 경과 건을 같은 쿼리로 비교합니다.
     * 두 경계 모두 MySQL 실제 조회에서 검증합니다.
     */
    @Test
    void pendingWebhookUsesFiveMinuteProcessingStartBoundary() {
        PaymentWebhookEvent recent = newEvent("Transaction.Ready", CUTOFF.plusSeconds(1));
        PaymentWebhookEvent stale = newEvent("Transaction.Ready", CUTOFF);

        // 정확한 경계까지는 남겨두고, 지난 항목만 선택합니다.
        assertFalse(recent.isRecoverable(CUTOFF));
        assertTrue(stale.isRecoverable(CUTOFF));
        List<Long> ids = recoverableIds();
        assertFalse(ids.contains(recent.getId()));
        assertTrue(ids.contains(stale.getId()));
    }

    /**
     * 재시도 중 PENDING으로 전환한 이벤트를 즉시 또 처리하지 않습니다.
     * 처리 시작 시각이 갱신되면 5분 고착 기준도 함께 갱신됩니다.
     * 동일 이벤트 재선점 시 일시적인 PortOne 지연을 구별합니다.
     */
    @Test
    void retryResetsPendingStalenessClock() {
        PaymentWebhookEvent event = newEvent("Transaction.Paid", NOW.minus(Duration.ofHours(1)));
        event.fail("PG_UNAVAILABLE", NOW.minus(Duration.ofMinutes(10)));
        event.retry(NOW.minus(Duration.ofMinutes(2)));
        repository.saveAndFlush(event);

        // 이전 실패 시각이 아니라 새 처리 시작 시각을 기준으로 검사합니다.
        assertFalse(event.isRecoverable(CUTOFF));
        assertFalse(recoverableIds().contains(event.getId()));
    }

    /**
     * 취소 Webhook은 결제 복구가 아니라 환불 복구의 소관입니다.
     * PENDING이 5분 이상 지났어도 eventTypes 필터에 따라 제외됩니다.
     * 결제 후보에 들어오지 않도록 실제 JPQL 결과를 검사합니다.
     */
    @Test
    void cancellationWebhookIsExcludedFromPaymentReconciliation() {
        PaymentWebhookEvent event = newEvent("Transaction.Cancelled", NOW.minus(Duration.ofHours(1)));

        // 도메인 상태만으로는 복구 가능한 PENDING이지만 유형 필터가 제외합니다.
        assertTrue(event.isRecoverable(CUTOFF));
        assertFalse(recoverableIds().contains(event.getId()));
    }

    /**
     * 처리 완료 이벤트는 아무리 오래되어도 복구하지 않습니다.
     * IGNORED 상태를 사용해 이미 종료된 이벤트의 동작을 재현합니다.
     * 상태와 시각 조건을 함께 만족해야 후보가 된다는 것을 검사합니다.
     */
    @Test
    void finishedWebhookIsNeverSelected() {
        PaymentWebhookEvent event = newEvent("Transaction.Failed", NOW.minus(Duration.ofHours(1)));
        event.ignore(NOW.minus(Duration.ofMinutes(30)));
        repository.saveAndFlush(event);

        // 과거 접수/종료 시각이어도 IGNORED는 다시 처리하지 않습니다.
        assertFalse(event.isRecoverable(CUTOFF));
        assertFalse(recoverableIds().contains(event.getId()));
    }

    /**
     * 데이터 격리를 위해 무작위 eventId를 갖는 JPA 이력을 저장합니다.
     * 실제 구매 ID나 결제 거래 ID를 만들지 않고 nullable 연관을 사용합니다.
     * 삭제할 Fixture ID는 생성 직후 등록합니다.
     */
    private PaymentWebhookEvent newEvent(String type, Instant receivedAt) {
        String uid = UUID.randomUUID().toString();
        PaymentWebhookEvent event = PaymentWebhookEvent.received(
                "j2-" + uid,
                "payment-j2-" + uid,
                null,
                type,
                "a".repeat(64),
                "{}",
                receivedAt
        );
        repository.saveAndFlush(event);
        createdIds.add(event.getId());
        return event;
    }

    /**
     * 운영 복구와 동일하게 네 종류 결제 이벤트만 요청합니다.
     * 정렬된 후보를 조회한 뒤 식별자만 비교해 LAZY 관계 접근을 피합니다.
     * 기존 테스트 DB의 다른 이력에는 어떠한 변경도 하지 않습니다.
     */
    private List<Long> recoverableIds() {
        return repository.findRecoverable(PAYMENT_TYPES, CUTOFF, PageRequest.of(0, 1000))
                .stream()
                .map(PaymentWebhookEvent::getId)
                .toList();
    }
}
