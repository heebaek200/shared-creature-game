package com.team.sharedcreature.payment.application;

import com.team.sharedcreature._core.exception.BusinessException;
import com.team.sharedcreature._core.exception.ErrorCode;
import com.team.sharedcreature.avatar.infrastructure.OwnedItemRepository;
import com.team.sharedcreature.payment.api.AdminRefundResponse;
import com.team.sharedcreature.payment.api.RefundResponse;
import com.team.sharedcreature.payment.domain.PaymentTransactionType;
import com.team.sharedcreature.payment.domain.RefundStatus;
import com.team.sharedcreature.payment.infrastructure.PaymentTransactionRepository;
import com.team.sharedcreature.payment.infrastructure.RefundRequestRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 사용자·관리자의 환불 목록과 관리자 상세 조회를 담당합니다.
 * PG 호출이나 환불 상태 변경은 수행하지 않습니다.
 */
@RequiredArgsConstructor
@Service
@Transactional(readOnly = true)
public class RefundQueryService {

    private final RefundRequestRepository refundRequestRepository;
    private final OwnedItemRepository ownedItemRepository;
    private final PaymentTransactionRepository transactionRepository;

    /**
     * 인증된 사용자가 신청한 환불 목록을 조회합니다.
     *
     * @param userId 인증된 신청 사용자 ID
     * @param page 페이지 번호
     * @param size 페이지 크기
     * @param status 환불 상태, null이면 전체
     * @return 본인의 환불 요청 페이지
     */
    public Page<RefundResponse> listMyRefunds(
            Long userId,
            int page,
            int size,
            RefundStatus status
    ) {
        PageRequest pageable = pageRequest(page, size);

        // 조회자 ID를 조건에 포함하여 다른 사용자의 요청을 제외합니다.
        return refundRequestRepository
                .findMyRefunds(userId, status, pageable)
                .map(RefundResponse::from);
    }

    /**
     * 관리자에게 전체 환불 목록을 제공합니다.
     *
     * @param page 페이지 번호
     * @param size 페이지 크기
     * @param status 환불 상태, null이면 전체
     * @return 전체 환불 요청 페이지
     */
    public Page<RefundResponse> listAdminRefunds(
            int page,
            int size,
            RefundStatus status
    ) {
        PageRequest pageable = pageRequest(page, size);

        return refundRequestRepository
                .findAdminRefunds(status, pageable)
                .map(RefundResponse::from);
    }

    /**
     * 관리자 환불 상세에 구매·보유 아이템·최근 취소 거래를 포함합니다.
     *
     * @param requestId 환불 요청 ID
     * @return 관리자 환불 상세 응답
     */
    public AdminRefundResponse getAdminRefund(Long requestId) {
        if (requestId == null || requestId < 1) {
            throw new BusinessException(
                    ErrorCode.INVALID_FIELD_VALUE,
                    "환불 요청 ID는 1 이상이어야 합니다."
            );
        }

        var refund = refundRequestRepository.findDetailById(requestId)
                .orElseThrow(() -> new BusinessException(
                        ErrorCode.RESOURCE_NOT_FOUND
                ));

        Long purchaseId = refund.getPurchase().getId();

        // 미지급 아이템과 취소 시도 전 거래는 null을 허용합니다.
        var item = ownedItemRepository.findByPurchaseId(purchaseId)
                .orElse(null);

        // 승인 거래가 아닌 취소 거래만 선택합니다.
        var cancellation = transactionRepository
                .findFirstByPurchaseIdAndTransactionTypeOrderByCreatedAtDescIdDesc(
                        purchaseId,
                        PaymentTransactionType.CANCELLATION
                )
                .orElse(null);

        return AdminRefundResponse.from(refund, item, cancellation);
    }

    /**
     * 페이지 입력을 검증한 뒤 조회 조건을 생성합니다.
     */
    private PageRequest pageRequest(int page, int size) {
        if (page < 0 || size < 1 || size > 50) {
            throw new BusinessException(
                    ErrorCode.INVALID_FIELD_VALUE,
                    "page는 0 이상, size는 1 이상 50 이하여야 합니다."
            );
        }

        return PageRequest.of(page, size);
    }
}