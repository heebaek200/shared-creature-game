package com.team.sharedcreature.payment.application;

import com.team.sharedcreature._core.exception.BusinessException;
import com.team.sharedcreature._core.exception.ErrorCode;
import com.team.sharedcreature.avatar.infrastructure.OwnedItemRepository;
import com.team.sharedcreature.payment.api.PurchaseResponse;
import com.team.sharedcreature.payment.api.PurchaseSummaryResponse;
import com.team.sharedcreature.payment.domain.PaymentTransaction;
import com.team.sharedcreature.payment.domain.Purchase;
import com.team.sharedcreature.payment.domain.RefundRequest;
import com.team.sharedcreature.payment.infrastructure.PaymentTransactionRepository;
import com.team.sharedcreature.payment.infrastructure.PurchaseRepository;
import com.team.sharedcreature.payment.infrastructure.RefundRequestRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 인증 사용자의 구매·거래·환불 Snapshot을 조회합니다. */
@Service
@Transactional(readOnly = true)
public class PurchaseQueryService {

    private final PurchaseRepository purchaseRepository;
    private final OwnedItemRepository ownedItemRepository;
    private final PaymentTransactionRepository transactionRepository;
    private final RefundRequestRepository refundRequestRepository;

    /** 구매 화면 Snapshot에 필요한 원장을 주입받습니다. */
    public PurchaseQueryService(
            PurchaseRepository purchaseRepository,
            OwnedItemRepository ownedItemRepository,
            PaymentTransactionRepository transactionRepository,
            RefundRequestRepository refundRequestRepository
    ) {
        this.purchaseRepository = purchaseRepository;
        this.ownedItemRepository = ownedItemRepository;
        this.transactionRepository = transactionRepository;
        this.refundRequestRepository = refundRequestRepository;
    }

    /** 다른 사용자의 구매 존재 여부를 노출하지 않고 상세를 반환합니다. */
    public PurchaseResponse get(Long userId, Long purchaseId) {
        Purchase purchase = purchaseRepository.findByIdAndUserId(purchaseId, userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND));
        return response(purchase, true);
    }

    /** 생성 시각 내림차순으로 사용자 구매 목록을 조회합니다. */
    public Page<PurchaseSummaryResponse> list(Long userId, int page, int size, String status) {
        PageRequest pageable = PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "createdAt", "id"));
        Page<Purchase> purchases = status == null || status.isBlank()
                ? purchaseRepository.findByUserId(userId, pageable)
                : purchaseRepository.findByUserIdAndStatus(userId, status, pageable);
        return purchases.map(this::summary);
    }

    private PurchaseSummaryResponse summary(Purchase purchase) {
        String ownedStatus = ownedItemRepository.findByPurchaseId(purchase.getId())
                .map(item -> item.getStatus())
                .orElse(null);
        String latestRefundStatus = refundRequestRepository
                .findFirstByPurchaseIdOrderByRequestedAtDesc(purchase.getId())
                .map(RefundRequest::getStatus)
                .orElse(null);
        return new PurchaseSummaryResponse(
                purchase.getId(),
                purchase.getOrderNumber(),
                purchase.getItemNameSnapshot(),
                purchase.getPriceKrwSnapshot(),
                purchase.getCurrency(),
                purchase.getStatus(),
                purchase.getCreatedAt(),
                ownedStatus,
                latestRefundStatus
        );
    }

    private PurchaseResponse response(Purchase purchase, boolean includeDetails) {
        String ownedStatus = ownedItemRepository.findByPurchaseId(purchase.getId())
                .map(item -> item.getStatus())
                .orElse(null);
        var refunds = refundRequestRepository.findByPurchaseIdOrderByRequestedAtAsc(purchase.getId());
        String latestRefundStatus = refunds.isEmpty() ? null : refunds.get(refunds.size() - 1).getStatus();
        var transactions = includeDetails
                ? transactionRepository.findByPurchaseIdOrderByCreatedAtAsc(purchase.getId()).stream()
                        .map(this::transaction)
                        .toList()
                : java.util.List.<PurchaseResponse.Transaction>of();
        var refundResponses = includeDetails
                ? refunds.stream().map(this::refund).toList()
                : java.util.List.<PurchaseResponse.Refund>of();
        return new PurchaseResponse(
                purchase.getId(),
                purchase.getOrderNumber(),
                purchase.getItemNameSnapshot(),
                purchase.getPriceKrwSnapshot(),
                purchase.getCurrency(),
                purchase.getStatus(),
                purchase.getCreatedAt(),
                ownedStatus,
                latestRefundStatus,
                purchase.getShopItem().getId(),
                transactions,
                refundResponses
        );
    }

    private PurchaseResponse.Transaction transaction(PaymentTransaction transaction) {
        return new PurchaseResponse.Transaction(
                transaction.getId(),
                transaction.getProvider(),
                transaction.getProviderTransactionId(),
                transaction.getTransactionType(),
                transaction.getAmountKrw(),
                transaction.getStatus(),
                transaction.getProcessedAt()
        );
    }

    private PurchaseResponse.Refund refund(RefundRequest refund) {
        return new PurchaseResponse.Refund(
                refund.getId(),
                refund.getPurchase().getId(),
                refund.getRequestedByUser().getId(),
                refund.getRequestReason(),
                refund.getDecisionReason(),
                refund.getStatus(),
                refund.getRequestedAt(),
                refund.getReviewedAt(),
                refund.getCompletedAt()
        );
    }
}
