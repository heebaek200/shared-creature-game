package com.team.sharedcreature.payment.application;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;
import com.team.sharedcreature._core.exception.BusinessException;
import com.team.sharedcreature._core.security.Hashing;
import com.team.sharedcreature.payment.domain.PaymentWebhookEvent;
import com.team.sharedcreature.payment.infrastructure.PaymentTransactionRepository;
import com.team.sharedcreature.payment.infrastructure.PaymentWebhookEventRepository;
import com.team.sharedcreature.payment.infrastructure.PurchaseRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.dao.DataIntegrityViolationException;

import java.time.Clock;
import java.util.Set;

/** 서명 검증된 PortOne 이벤트를 보존하고 결제 정합화로 전달합니다. */
@Service
public class PaymentWebhookService {

    private static final Set<String> RECONCILABLE_TYPES = Set.of(
            "Transaction.Ready",
            "Transaction.Paid",
            "Transaction.Failed",
            "Transaction.PayPending"
    );

    private final PortOneWebhookVerifier verifier;
    private final ObjectMapper objectMapper;
    private final PaymentWebhookEventRepository eventRepository;
    private final PurchaseRepository purchaseRepository;
    private final PaymentTransactionRepository transactionRepository;
    private final PaymentReconciliationService reconciliationService;
    private final TransactionTemplate transactionTemplate;
    private final Clock clock;

    /** Webhook 검증·원장·결제 정합화 의존성을 주입받습니다. */
    public PaymentWebhookService(
            PortOneWebhookVerifier verifier,
            ObjectMapper objectMapper,
            PaymentWebhookEventRepository eventRepository,
            PurchaseRepository purchaseRepository,
            PaymentTransactionRepository transactionRepository,
            PaymentReconciliationService reconciliationService,
            TransactionTemplate transactionTemplate,
            Clock clock
    ) {
        this.verifier = verifier;
        this.objectMapper = objectMapper;
        this.eventRepository = eventRepository;
        this.purchaseRepository = purchaseRepository;
        this.transactionRepository = transactionRepository;
        this.reconciliationService = reconciliationService;
        this.transactionTemplate = transactionTemplate;
        this.clock = clock;
    }

    /** 원본 서명 검증 뒤 이벤트를 먼저 저장하고 외부 조회는 트랜잭션 밖에서 수행합니다. */
    public void receive(String body, String eventId, String signature, String timestamp) {
        verifier.verify(body, eventId, signature, timestamp);
        ParsedWebhook parsed = parse(body);
        Long eventDatabaseId;
        try {
            eventDatabaseId = transactionTemplate.execute(status -> persist(eventId, parsed, body));
        } catch (DataIntegrityViolationException exception) {
            // 동시에 같은 공급자 이벤트를 저장한 요청은 중복 수신으로 처리합니다.
            return;
        }
        if (eventDatabaseId == null) {
            return;
        }
        if (!parsed.knownPaymentEvent()) {
            transactionTemplate.executeWithoutResult(status -> eventRepository.findByIdForUpdate(eventDatabaseId)
                    .ifPresent(event -> event.ignore(clock.instant())));
            return;
        }
        if (parsed.cancellationEvent()) {
            // #22가 환불 원장을 함께 잠가 확정할 수 있도록 PENDING으로 보존합니다.
            return;
        }
        try {
            var result = reconciliationService.reconcileByPaymentId(parsed.paymentId());
            transactionTemplate.executeWithoutResult(status -> eventRepository.findByIdForUpdate(eventDatabaseId)
                    .ifPresent(event -> event.complete(
                            transactionRepository.findById(result.transactionId()).orElse(null),
                            clock.instant()
                    )));
        } catch (BusinessException | PaymentGatewayException exception) {
            transactionTemplate.executeWithoutResult(status -> eventRepository.findByIdForUpdate(eventDatabaseId)
                    .ifPresent(event -> event.fail(safeFailure(exception), clock.instant())));
        }
    }

    /** 저장된 이벤트 식별자만 사용해 PortOne 최신 상태로 고착 처리를 복구합니다. */
    public void recover(Long eventDatabaseId) {
        RecoverableEvent recoverable = transactionTemplate.execute(status -> eventRepository
                .findByIdForUpdate(eventDatabaseId)
                .map(event -> {
                    if (isCancellation(event.getEventType()) || event.getProviderPaymentId() == null) {
                        return null;
                    }
                    event.retry(clock.instant());
                    return new RecoverableEvent(event.getId(), event.getProviderPaymentId());
                })
                .orElse(null));
        if (recoverable == null) {
            return;
        }
        try {
            var result = reconciliationService.reconcileByPaymentId(recoverable.paymentId());
            transactionTemplate.executeWithoutResult(status -> eventRepository.findByIdForUpdate(recoverable.eventId())
                    .ifPresent(event -> event.complete(
                            transactionRepository.findById(result.transactionId()).orElse(null),
                            clock.instant()
                    )));
        } catch (BusinessException | PaymentGatewayException exception) {
            transactionTemplate.executeWithoutResult(status -> eventRepository.findByIdForUpdate(recoverable.eventId())
                    .ifPresent(event -> event.fail(safeFailure(exception), clock.instant())));
        }
    }

    private Long persist(String eventId, ParsedWebhook parsed, String body) {
        var duplicate = eventRepository.findByProviderAndProviderEventId("PORTONE", eventId);
        if (duplicate.isPresent()) {
            return null;
        }
        var purchase = parsed.paymentId() == null
                ? null
                : purchaseRepository.findByOrderNumber(parsed.paymentId()).orElse(null);
        PaymentWebhookEvent event = eventRepository.save(PaymentWebhookEvent.received(
                eventId,
                parsed.paymentId(),
                purchase,
                parsed.type(),
                Hashing.sha256(body),
                parsed.summary(),
                clock.instant()
        ));
        if (purchase == null && !parsed.cancellationEvent()) {
            // 미등록 결제 이벤트는 식별자와 요약을 남기되 반복 조회 대상에서는 제외합니다.
            event.ignore(clock.instant());
            return null;
        }
        return event.getId();
    }

    private ParsedWebhook parse(String body) {
        try {
            JsonNode root = objectMapper.readTree(body);
            String type = text(root, "type");
            JsonNode data = root.path("data");
            String paymentId = text(data, "paymentId");
            ObjectNode summary = objectMapper.createObjectNode();
            copy(summary, "type", type);
            copy(summary, "timestamp", text(root, "timestamp"));
            copy(summary, "storeId", text(data, "storeId"));
            copy(summary, "paymentId", paymentId);
            copy(summary, "transactionId", text(data, "transactionId"));
            copy(summary, "cancellationId", text(data, "cancellationId"));
            boolean cancellation = isCancellation(type);
            boolean known = paymentId != null && (RECONCILABLE_TYPES.contains(type) || cancellation);
            return new ParsedWebhook(type == null ? "UNKNOWN" : type, paymentId, summary.toString(), known, cancellation);
        } catch (JacksonException exception) {
            throw new PortOneWebhookVerifier.InvalidWebhookSignatureException(exception);
        }
    }

    private String safeFailure(RuntimeException exception) {
        if (exception instanceof BusinessException businessException) {
            return businessException.code();
        }
        if (exception instanceof PaymentGatewayException gatewayException) {
            return "PORTONE_" + gatewayException.kind().name();
        }
        return "PAYMENT_RECONCILIATION_FAILED";
    }

    private String text(JsonNode node, String field) {
        JsonNode value = node.path(field);
        return value.isTextual() ? value.textValue() : null;
    }

    private void copy(ObjectNode target, String field, String value) {
        if (value != null) {
            target.put(field, value);
        }
    }

    private boolean isCancellation(String type) {
        return "Transaction.Cancelled".equals(type)
                || "Transaction.PartialCancelled".equals(type)
                || "Transaction.CancelPending".equals(type);
    }

    private record ParsedWebhook(
            String type,
            String paymentId,
            String summary,
            boolean knownPaymentEvent,
            boolean cancellationEvent
    ) {
    }

    private record RecoverableEvent(Long eventId, String paymentId) {
    }
}
