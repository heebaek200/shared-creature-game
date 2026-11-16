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
            String status
    ) {
        PageRequest pageable = pageRequest(page, size);
        RefundStatus refundStatus = parseStatus(status);

        // 조회자 ID를 조건에 포함하여 다른 사용자의 요청을 제외합니다.
        return refundRequestRepository
                .findMyRefunds(userId, refundStatus, pageable)
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
            String status
    ) {
        PageRequest pageable = pageRequest(page, size);
        RefundStatus refundStatus = parseStatus(status);

        return refundRequestRepository
                .findAdminRefunds(refundStatus, pageable)
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
                        PaymentTransactionType.CANCELLATION.name()
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

    /**
     * 선택적인 환불 상태 필터를 도메인 타입으로 변환합니다.
     *
     * @param status 요청 환불 상태, 미지정이면 null
     * @return 환불 상태, 미지정이면 null
     * @throws BusinessException 허용되지 않은 환불 상태인 경우
     */
    private RefundStatus parseStatus(String status) {
        if (status == null) {
            return null;
        }

        try {
            // 대소문자와 공백을 보정하지 않고 기존 허용값을 유지합니다.
            return RefundStatus.valueOf(status);
        } catch (IllegalArgumentException exception) {
            throw new BusinessException(
                    ErrorCode.INVALID_FIELD_VALUE,
                    "지원하지 않는 환불 상태입니다."
            );
        }
    }
}