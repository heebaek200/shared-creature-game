package com.team.sharedcreature.payment.application;

import com.team.sharedcreature._core.exception.BusinessException;
import com.team.sharedcreature._core.exception.ErrorCode;
import com.team.sharedcreature.avatar.domain.OwnedItem;
import com.team.sharedcreature.avatar.infrastructure.OwnedItemRepository;
import com.team.sharedcreature.payment.api.PurchaseResponse;
import com.team.sharedcreature.payment.domain.PaymentTransaction;
import com.team.sharedcreature.payment.domain.PaymentTransactionType;
import com.team.sharedcreature.payment.domain.Purchase;
import com.team.sharedcreature.payment.domain.PurchaseStatus;
import com.team.sharedcreature.payment.infrastructure.PaymentTransactionRepository;
import com.team.sharedcreature.payment.infrastructure.PortOneProperties;
import com.team.sharedcreature.payment.infrastructure.PurchaseRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.util.Objects;

/** 브라우저·Webhook·복구 경로가 공유하는 결제 검증과 1회 지급을 처리합니다. */
@Service
public class PaymentReconciliationService {

    private final PurchaseRepository purchaseRepository;
    private final PaymentTransactionRepository transactionRepository;
    private final OwnedItemRepository ownedItemRepository;
    private final PaymentGateway paymentGateway;
    private final PurchaseQueryService queryService;
    private final PortOneProperties properties;
    private final TransactionTemplate transactionTemplate;
    private final Clock clock;

    /** 외부 조회와 DB 확정 사이의 트랜잭션 경계를 구성합니다. */
    public PaymentReconciliationService(
            PurchaseRepository purchaseRepository,
            PaymentTransactionRepository transactionRepository,
            OwnedItemRepository ownedItemRepository,
            PaymentGateway paymentGateway,
            PurchaseQueryService queryService,
            PortOneProperties properties,
            TransactionTemplate transactionTemplate,
            Clock clock
    ) {
        this.purchaseRepository = purchaseRepository;
        this.transactionRepository = transactionRepository;
        this.ownedItemRepository = ownedItemRepository;
        this.paymentGateway = paymentGateway;
        this.queryService = queryService;
        this.properties = properties;
        this.transactionTemplate = transactionTemplate;
        this.clock = clock;
    }

    /** 인증 사용자 주문을 PortOne 최신 상태로 검증하고 화면 Snapshot을 반환합니다. */
    public PurchaseResponse reconcileForUser(Long userId, Long purchaseId, String paymentId) {
        Purchase expected = purchaseRepository.findByIdAndUserId(purchaseId, userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND));
        if (!expected.getOrderNumber().equals(paymentId)) {
            throw new BusinessException(ErrorCode.PAYMENT_RESULT_UNKNOWN);
        }
        PaymentSnapshot actual;
        try {
            actual = paymentGateway.getPayment(paymentId);
        } catch (PaymentGatewayException exception) {
            throw new BusinessException(ErrorCode.PAYMENT_RESULT_UNKNOWN);
        }
        reconcile(expected.getId(), expectedSnapshot(expected), actual);
        return queryService.get(userId, purchaseId);
    }

    /** Webhook이나 복구 작업이 공급자 paymentId로 동일한 정합화 흐름을 실행합니다. */
    public ReconciliationResult reconcileByPaymentId(String paymentId) {
        Purchase expected = purchaseRepository.findByOrderNumber(paymentId)
                .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND));
        return reconcile(expected.getId(), expectedSnapshot(expected), paymentGateway.getPayment(paymentId));
    }

    private ExpectedPurchase expectedSnapshot(Purchase purchase) {
        return new ExpectedPurchase(
                purchase.getOrderNumber(),
                purchase.getPriceKrwSnapshot(),
                purchase.getCurrency()
        );
    }

    private ReconciliationResult reconcile(
            Long purchaseId,
            ExpectedPurchase expected,
            PaymentSnapshot actual
    ) {
        validate(expected, actual);
        if (actual.status() == PaymentSnapshot.Status.READY || actual.status() == PaymentSnapshot.Status.PENDING) {
            throw new BusinessException(ErrorCode.PAYMENT_PENDING);
        }
        if (actual.status() == PaymentSnapshot.Status.UNKNOWN
                || actual.status() == PaymentSnapshot.Status.CANCELLED
                || actual.status() == PaymentSnapshot.Status.PARTIAL_CANCELLED) {
            throw new BusinessException(ErrorCode.PAYMENT_RESULT_UNKNOWN);
        }

        ReconciliationResult result = transactionTemplate.execute(status -> {
            Purchase locked = purchaseRepository.findByIdForUpdate(purchaseId)
                    .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND));
            if (actual.status() == PaymentSnapshot.Status.FAILED) {
                locked.markFailed(clock.instant());
                PaymentTransaction transaction = transactionRepository
                        .findFirstByPurchaseIdAndTransactionTypeOrderByCreatedAtDesc(
                                purchaseId,
                                PaymentTransactionType.APPROVAL.name()
                        )
                        .orElseGet(() -> transactionRepository.save(PaymentTransaction.failed(
                                locked,
                                locked.getPriceKrwSnapshot(),
                                clock.instant()
                        )));
                return new ReconciliationResult(locked.getId(), transaction.getId(), locked.getStatus());
            }

            if (PurchaseStatus.FAILED.name().equals(locked.getStatus())
                    || PurchaseStatus.CANCELLED.name().equals(locked.getStatus())) {
                throw new BusinessException(ErrorCode.PAYMENT_RESULT_UNKNOWN);
            }
            if (!PurchaseStatus.PAID.name().equals(locked.getStatus())) {
                locked.markPaid(clock.instant());
            }
            PaymentTransaction transaction = transactionRepository
                    .findByProviderAndProviderTransactionId("PORTONE", actual.transactionId())
                    .orElseGet(() -> transactionRepository.save(PaymentTransaction.approved(
                            locked,
                            actual.transactionId(),
                            actual.totalAmount(),
                            actual.paidAt() == null ? clock.instant() : actual.paidAt(),
                            clock.instant()
                    )));
            if (!Objects.equals(transaction.getPurchase().getId(), locked.getId())) {
                throw new BusinessException(ErrorCode.PAYMENT_RESULT_UNKNOWN);
            }
            ownedItemRepository.findByPurchaseId(purchaseId)
                    .orElseGet(() -> ownedItemRepository.save(OwnedItem.grant(locked, clock.instant())));
            return new ReconciliationResult(locked.getId(), transaction.getId(), locked.getStatus());
        });
        if (result == null) {
            throw new IllegalStateException("결제 정합화 트랜잭션 결과가 없습니다.");
        }
        return result;
    }

    private void validate(ExpectedPurchase expected, PaymentSnapshot actual) {
        if (actual.totalAmount() != expected.amount()) {
            throw new BusinessException(ErrorCode.PAYMENT_AMOUNT_MISMATCH);
        }
        boolean identityValid = expected.paymentId().equals(actual.paymentId())
                && properties.storeId().equals(actual.storeId())
                && expected.currency().equals(actual.currency());
        if (!identityValid) {
            throw new BusinessException(ErrorCode.PAYMENT_RESULT_UNKNOWN);
        }
        if (actual.status() == PaymentSnapshot.Status.PAID) {
            boolean approvalValid = "EASY_PAY".equals(actual.payMethod())
                    && "KAKAOPAY".equals(actual.easyPayProvider())
                    && "TEST".equals(actual.channelType())
                    && actual.transactionId() != null
                    && !actual.transactionId().isBlank();
            if (!approvalValid) {
                throw new BusinessException(ErrorCode.PAYMENT_RESULT_UNKNOWN);
            }
        }
    }

    private record ExpectedPurchase(String paymentId, long amount, String currency) {
    }

    /** 정합화된 구매와 승인 거래 식별자입니다. */
    public record ReconciliationResult(Long purchaseId, Long transactionId, String purchaseStatus) {
    }
}
