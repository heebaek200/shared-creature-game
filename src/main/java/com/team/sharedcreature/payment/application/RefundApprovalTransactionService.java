package com.team.sharedcreature.payment.application;

import com.team.sharedcreature._core.exception.BusinessException;
import com.team.sharedcreature._core.exception.ErrorCode;
import com.team.sharedcreature.auth.infrastructure.UserRepository;
import com.team.sharedcreature.payment.domain.PgApiAttempt;
import com.team.sharedcreature.payment.infrastructure.PgApiAttemptRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;

/**
 * PG 호출 전에 관리자 승인과 취소 준비 이력을 확정합니다.
 * 외부 PG 통신은 이 Service에서 수행하지 않습니다.
 */
@RequiredArgsConstructor
@Service
public class RefundApprovalTransactionService {

    private final UserRepository userRepository;
    private final RefundLockService refundLockService;
    private final PgApiAttemptRepository pgApiAttemptRepository;
    private final Clock clock;

    /**
     * 미사용 여부를 재검증하고 최초 취소 처리권을 선점합니다.
     *
     * @param adminUserId 인증된 관리자 ID
     * @param requestId 승인할 환불 요청 ID
     * @param decisionReason 관리자 승인 사유
     * @return Commit 이후 PG 호출에 사용할 값
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public CancellationCommand prepare(
            Long adminUserId,
            Long requestId,
            String decisionReason
    ) {
        if (adminUserId == null) {
            throw new BusinessException(ErrorCode.ROLE_FORBIDDEN);
        }

        var reviewer = userRepository.findById(adminUserId)
                .filter(user ->
                        "ACTIVE".equals(user.getStatus())
                                && "SUPER_ADMIN".equals(user.getGlobalRole())
                )
                .orElseThrow(() ->
                        new BusinessException(ErrorCode.ROLE_FORBIDDEN)
                );

        // 구매 → 보유 아이템 → 환불 요청 순서로 잠급니다.
        var locked = refundLockService.lock(requestId);
        var purchase = locked.purchase();
        var item = locked.item();
        var refund = locked.refund();

        if (item == null) {
            throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND);
        }

        // 연결된 구매·아이템·신청자의 소유 관계를 재검증합니다.
        Long ownerId = purchase.getUser().getId();

        if (!ownerId.equals(item.getUser().getId())
                || !ownerId.equals(refund.getRequestedByUser().getId())) {
            throw new BusinessException(
                    ErrorCode.CONCURRENT_MODIFICATION,
                    "환불 요청과 구매·아이템의 소유자가 일치하지 않습니다."
            );
        }

        if (!"PAID".equals(purchase.getStatus())) {
            throw new BusinessException(
                    ErrorCode.CONCURRENT_MODIFICATION,
                    "결제 완료 구매만 환불을 승인할 수 있습니다."
            );
        }

        if (item.getUsedAt() != null || "OWNED_USED".equals(item.getStatus())) {
            throw new BusinessException(ErrorCode.REFUND_NOT_ALLOWED_USED_ITEM);
        }

        if (!"OWNED_UNUSED".equals(item.getStatus())
                || item.getRevokedAt() != null) {
            throw new BusinessException(
                    ErrorCode.CONCURRENT_MODIFICATION,
                    "유효한 미사용 아이템만 환불을 승인할 수 있습니다."
            );
        }

        // 잠금 대기와 검증이 끝난 뒤의 시각으로 승인·선점을 기록합니다.
        var now = clock.instant();
        refund.claimCancellation(reviewer, decisionReason, now);

        // 최초 승인에서는 취소 시도 번호 1을 사용합니다.
        var attempt = pgApiAttemptRepository.save(
                PgApiAttempt.prepareCancellation(refund, 1, now)
        );

        // 관리 중인 엔티티를 외부 호출 단계로 전달하지 않습니다.
        return new CancellationCommand(
                refund.getId(),
                purchase.getId(),
                attempt.getId(),
                purchase.getOrderNumber(),
                purchase.getPriceKrwSnapshot(),
                purchase.getCurrency(),
                refund.getDecisionReason(),
                attempt.getIdempotencyKey()
        );
    }

    /**
     * 트랜잭션 종료 후 PG 호출에 사용할 불변 값입니다.
     *
     * @param refundRequestId 환불 요청 ID
     * @param purchaseId 구매 ID
     * @param pgApiAttemptId 취소 준비 이력 ID
     * @param paymentId PortOne 결제 ID로 사용하는 내부 주문 번호
     * @param amountKrw 검증에 사용할 구매 금액
     * @param currency 구매 통화
     * @param reason 정규화된 관리자 승인 사유
     * @param pgIdempotencyKey 저장된 PG 취소 멱등 키
     */
    public record CancellationCommand(
            Long refundRequestId,
            Long purchaseId,
            Long pgApiAttemptId,
            String paymentId,
            Long amountKrw,
            String currency,
            String reason,
            String pgIdempotencyKey
    ) {
    }
}