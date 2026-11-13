package com.team.sharedcreature.payment.application;

import com.team.sharedcreature._core.exception.BusinessException;
import com.team.sharedcreature._core.exception.ErrorCode;
import com.team.sharedcreature.avatar.domain.OwnedItem;
import com.team.sharedcreature.avatar.infrastructure.OwnedItemRepository;
import com.team.sharedcreature.payment.domain.Purchase;
import com.team.sharedcreature.payment.domain.RefundRequest;
import com.team.sharedcreature.payment.infrastructure.PurchaseRepository;
import com.team.sharedcreature.payment.infrastructure.RefundRequestRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 환불 명령에서 구매·보유 아이템·환불 요청의 잠금 순서를 통일합니다.
 * 호출자가 시작한 트랜잭션 안에서만 사용합니다.
 */
@RequiredArgsConstructor
@Service
public class RefundLockService {

    private final PurchaseRepository purchaseRepository;
    private final OwnedItemRepository ownedItemRepository;
    private final RefundRequestRepository refundRequestRepository;

    /**
     * 환불 요청에 연결된 데이터를 정해진 순서로 잠급니다.
     *
     * @param requestId 처리할 환불 요청 ID
     * @return 현재 트랜잭션에서 잠근 구매·아이템·환불 요청
     * @throws BusinessException ID가 유효하지 않거나 대상이 없는 경우
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public LockedRefund lock(Long requestId) {
        if (requestId == null || requestId < 1) {
            throw new BusinessException(
                    ErrorCode.INVALID_FIELD_VALUE,
                    "환불 요청 ID는 1 이상이어야 합니다."
            );
        }

        // 사전 조회: 구매부터 잠그기 위해 ID만 확인합니다.
        Long purchaseId = refundRequestRepository
                .findPurchaseIdByRequestId(requestId)
                .orElseThrow(() ->
                        new BusinessException(ErrorCode.RESOURCE_NOT_FOUND)
                );

        // 잠금 1: 구매.
        Purchase purchase = purchaseRepository.findByIdForUpdate(purchaseId)
                .orElseThrow(() ->
                        new BusinessException(ErrorCode.RESOURCE_NOT_FOUND)
                );

        // 잠금 2: 보유 아이템. 미지급이면 null로 유지합니다.
        OwnedItem item = ownedItemRepository
                .findByPurchaseIdForUpdate(purchaseId)
                .orElse(null);

        // 잠금 3: 해당 구매의 환불 이력을 ID 순서대로 잠급니다.
        RefundRequest refund = refundRequestRepository
                .findAllByPurchaseIdForUpdate(purchaseId)
                .stream()
                .filter(candidate -> requestId.equals(candidate.getId()))
                .findFirst()
                .orElseThrow(() ->
                        new BusinessException(ErrorCode.RESOURCE_NOT_FOUND)
                );

        return new LockedRefund(purchase, item, refund);
    }

    /**
     * 현재 트랜잭션에서 잠근 엔티티를 전달합니다.
     * 트랜잭션 밖으로 전달하거나 PG 호출에 사용하지 않습니다.
     *
     * @param purchase 잠근 구매
     * @param item 잠근 보유 아이템, 미지급이면 null
     * @param refund 잠근 환불 요청
     */
    public record LockedRefund(
            Purchase purchase,
            OwnedItem item,
            RefundRequest refund
    ) {
    }
}