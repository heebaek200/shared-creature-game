package com.team.sharedcreature.payment.application;

import com.team.sharedcreature._core.exception.BusinessException;
import com.team.sharedcreature._core.exception.ErrorCode;
import com.team.sharedcreature.admin.application.ApiIdempotencyService;
import com.team.sharedcreature.avatar.infrastructure.OwnedItemRepository;
import com.team.sharedcreature.avatar.domain.OwnedItem;
import com.team.sharedcreature.avatar.domain.OwnedItemStatus;
import com.team.sharedcreature.payment.api.RefundResponse;
import com.team.sharedcreature.payment.domain.Purchase;
import com.team.sharedcreature.payment.domain.RefundRequest;
import com.team.sharedcreature.payment.domain.RefundStatus;
import com.team.sharedcreature.payment.domain.PurchaseStatus;
import com.team.sharedcreature.payment.infrastructure.PurchaseRepository;
import com.team.sharedcreature.payment.infrastructure.RefundRequestRepository;
import com.team.sharedcreature.auth.domain.User;
import com.team.sharedcreature.auth.infrastructure.UserRepository;
import com.team.sharedcreature.payment.api.AdminRefundResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import com.team.sharedcreature.auth.domain.GlobalRole;
import com.team.sharedcreature.auth.domain.UserStatus;
import org.springframework.transaction.annotation.Transactional;
import com.team.sharedcreature.payment.application.RefundApprovalTransactionService.CommandResponse;
import org.springframework.transaction.annotation.Propagation;

import java.time.Clock;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Set;

/**
 * 환불 요청 생성과 관리자 환불 명령을 담당합니다.
 * 외부 PG 호출의 트랜잭션 경계는 명령별로 분리합니다.
 */
@RequiredArgsConstructor
@Service
public class RefundService {

    private static final String CREATE_OPERATION = "createRefundRequest";

    /** 중복 환불 요청을 차단해야 하는 진행 중 상태입니다. */
    private static final Set<RefundStatus> ACTIVE_REFUND_STATUSES = Set.of(
            RefundStatus.REQUESTED,
            RefundStatus.CANCEL_PENDING,
            RefundStatus.CANCEL_UNKNOWN
    );

    private final PurchaseRepository purchaseRepository;
    private final OwnedItemRepository ownedItemRepository;
    private final RefundRequestRepository refundRequestRepository;
    private final ApiIdempotencyService idempotencyService;
    private final Clock clock;
    private final UserRepository userRepository;
    private final RefundLockService refundLockService;
    private final RefundQueryService refundQueryService;
    private final RefundApprovalTransactionService approvalTransactionService;
    private final RefundGateway refundGateway;
    private final RefundResultTransactionService resultTransactionService;

    /**
     * 본인의 결제 완료·미사용 아이템에 대해 환불 요청을 생성합니다.
     * 요청 생성과 최초 응답 저장은 하나의 트랜잭션으로 확정합니다.
     *
     * @param userId 인증된 신청 사용자 ID
     * @param purchaseId 환불 대상 구매 ID
     * @param reason 환불 요청 사유
     * @param idempotencyKey 검증된 요청 멱등 UUID
     * @return 생성한 환불 요청 또는 동일 요청의 최초 응답
     */
    @Transactional
    public RefundResponse createRefundRequest(
            Long userId,
            Long purchaseId,
            String reason,
            String idempotencyKey
    ) {
        if (purchaseId == null || purchaseId < 1) {
            throw new BusinessException(
                    ErrorCode.INVALID_FIELD_VALUE,
                    "구매 ID는 1 이상이어야 합니다."
            );
        }

        String normalizedReason = normalizeRequestReason(reason);

        // 대상 구매와 정규화한 사유를 모두 멱등 요청 비교에 포함합니다.
        ApiIdempotencyService.Claim<RefundResponse> claim = idempotencyService.claim(
                userId,
                CREATE_OPERATION,
                idempotencyKey,
                "purchaseId=" + purchaseId + ";reason=" + normalizedReason,
                RefundResponse.class
        );

        if (claim.replayed()) {
            return claim.response();
        }

        // 잠금 1: 구매. 다른 사용자의 구매는 노출하지 않습니다.
        Purchase purchase = purchaseRepository.findByIdForUpdate(purchaseId)
                .orElseThrow(() ->
                        new BusinessException(ErrorCode.RESOURCE_NOT_FOUND)
                );

        if (!userId.equals(purchase.getUser().getId())) {
            throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND);
        }

        // 잠금 2: 구매에 연결된 보유 아이템.
        OwnedItem item = ownedItemRepository.findByPurchaseIdForUpdate(purchaseId)
                .orElseThrow(() ->
                        new BusinessException(ErrorCode.RESOURCE_NOT_FOUND)
                );

        // 잠금 3: 같은 구매의 환불 이력.
        List<RefundRequest> refunds = refundRequestRepository
                .findAllByPurchaseIdForUpdate(purchaseId);

        if (purchase.getStatus() != PurchaseStatus.PAID) {
            throw new BusinessException(
                    ErrorCode.RESOURCE_NOT_FOUND,
                    "환불 가능한 결제 완료 구매를 찾을 수 없습니다."
            );
        }

        // 상태와 최초 사용 이력을 함께 확인합니다.
        if (item.getUsedAt() != null
                || item.getStatus() == OwnedItemStatus.OWNED_USED) {
            throw new BusinessException(ErrorCode.REFUND_NOT_ALLOWED_USED_ITEM);
        }

        if (item.getStatus() != OwnedItemStatus.OWNED_UNUSED
                || item.getRevokedAt() != null
                || !userId.equals(item.getUser().getId())) {
            throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND);
        }

        boolean alreadyActive = refunds.stream()
                .anyMatch(refund ->
                        ACTIVE_REFUND_STATUSES.contains(refund.getStatus())
                );

        if (alreadyActive) {
            throw new BusinessException(ErrorCode.REFUND_ALREADY_ACTIVE);
        }

        // 잠금 대기 이후의 서버 시각으로 요청을 생성합니다.
        RefundRequest refund = refundRequestRepository.save(
                RefundRequest.create(
                        purchase,
                        purchase.getUser(),
                        normalizedReason,
                        clock.instant()
                )
        );

        RefundResponse response = RefundResponse.from(refund);

        // 환불 요청과 최초 성공 응답을 함께 Commit합니다.
        idempotencyService.complete(
                claim,
                201,
                "REFUND_REQUEST",
                refund.getId(),
                response
        );

        return response;
    }

    /**
     * 본인이 요청한 미심사 환불을 철회하고 결제·아이템 상태는 유지합니다.
     * 같은 요청을 다시 철회하면 최초 철회 시각을 보존한 현재 응답을 반환합니다.
     *
     * @param userId 인증된 요청 사용자 ID
     * @param requestId 철회할 환불 요청 ID
     * @return WITHDRAWN 상태의 환불 요청
     */
    @Transactional
    public RefundResponse withdrawRefundRequest(Long userId, Long requestId) {
        RefundLockService.LockedRefund locked = refundLockService.lock(requestId);
        RefundRequest refund = locked.refund();

        // 다른 사용자의 요청 존재 여부는 노출하지 않습니다.
        if (userId == null || !userId.equals(refund.getRequestedByUser().getId())) {
            throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND);
        }

        // MySQL DATETIME(6)와 응답 정밀도를 맞춰 재호출 응답의 철회 시각을 동일하게 유지합니다.
        refund.withdraw(clock.instant().truncatedTo(ChronoUnit.MICROS));
        return RefundResponse.from(refund);
    }

    /**
     * 관리자 판단 사유를 기록하고 미심사 환불 요청을 거절합니다.
     * PG 취소나 구매·아이템 상태 변경은 수행하지 않습니다.
     *
     * @param adminUserId 인증된 관리자 ID
     * @param requestId 거절할 환불 요청 ID
     * @param decisionReason 관리자 거절 사유
     * @return 거절 결과를 포함한 관리자 상세 응답
     */
    @Transactional
    public AdminRefundResponse rejectRefund(
            Long adminUserId,
            Long requestId,
            String decisionReason
    ) {
        User reviewer = requireSuperAdmin(adminUserId);

        // 구매 → 보유 아이템 → 환불 요청 순서로 잠급니다.
        RefundLockService.LockedRefund locked = refundLockService.lock(requestId);

        // 잠금 획득 후 REQUESTED 상태와 사유를 검사하고 변경합니다.
        locked.refund().reject(
                reviewer,
                decisionReason,
                clock.instant()
        );

        // 같은 트랜잭션 안에서 변경 결과를 DTO로 변환합니다.
        return refundQueryService.getAdminRefund(requestId);
    }

    /**
     * 승인 준비, 외부 PG 취소 호출, 결과 저장을 순서대로 연결합니다.
     * PG 호출 중 DB 트랜잭션이 유지되지 않도록 구성합니다.
     *
     * @param adminUserId 요청한 관리자 ID
     * @param requestId 승인할 환불 요청 ID
     * @param decisionReason 관리자 승인 사유
     * @param idempotencyKey REST 요청의 멱등 UUID
     * @param traceId 현재 요청의 추적 ID
     * @return 최초 HTTP 상태와 응답 또는 기존 요청의 저장된 응답
     */
    @Transactional(propagation = Propagation.NEVER)
    public CommandResponse approveRefund(
            Long adminUserId,
            Long requestId,
            String decisionReason,
            String idempotencyKey,
            String traceId
    ) {
        // 별도 트랜잭션에서 처리권을 선점하고 Commit까지 완료합니다.
        RefundApprovalTransactionService.Preparation preparation = approvalTransactionService.prepare(
                adminUserId,
                requestId,
                decisionReason,
                idempotencyKey
        );

        // 완료된 동일 요청은 PG를 다시 호출하지 않습니다.
        if (preparation.replayResponse() != null) {
            return preparation.replayResponse();
        }

        RefundApprovalTransactionService.CancellationCommand command = preparation.command();

        // 승인 준비 트랜잭션이 끝난 뒤 PG 전체 취소를 호출합니다.
        RefundGateway.CancellationResult result = refundGateway.cancelFully(
                command.paymentId(),
                command.reason(),
                command.pgIdempotencyKey()
        );

        // 별도 트랜잭션에서 환불 상태와 최초 HTTP 응답을 함께 저장합니다.
        return resultTransactionService.apply(
                command,
                result,
                traceId
        );
    }

    /**
     * 현재 활성 상태인 전체 관리자 계정을 확인합니다.
     *
     * @param adminUserId 인증된 관리자 ID
     * @return 심사 정보에 기록할 관리자
     * @throws BusinessException 활성 전체 관리자 권한이 없는 경우
     */
    private User requireSuperAdmin(Long adminUserId) {
        if (adminUserId == null) {
            throw new BusinessException(ErrorCode.ROLE_FORBIDDEN);
        }

        User admin = userRepository.findById(adminUserId)
                .orElseThrow(() ->
                        new BusinessException(ErrorCode.ROLE_FORBIDDEN)
                );

        if (admin.getStatus() != UserStatus.ACTIVE
                || admin.getGlobalRole() != GlobalRole.SUPER_ADMIN) {
            throw new BusinessException(
                    ErrorCode.ROLE_FORBIDDEN,
                    "전체 관리자만 환불을 처리할 수 있습니다."
            );
        }

        return admin;
    }

    /**
     * HTTP 이외의 호출에서도 사유의 필수값과 길이를 검사합니다.
     */
    private String normalizeRequestReason(String reason) {
        if (reason == null) {
            throw new BusinessException(
                    ErrorCode.INVALID_FIELD_VALUE,
                    "환불 요청 사유는 필수입니다."
            );
        }

        String normalized = reason.trim();

        if (normalized.isBlank() || normalized.length() > 500) {
            throw new BusinessException(
                    ErrorCode.INVALID_FIELD_VALUE,
                    "환불 요청 사유는 앞뒤 공백 제거 후 1자 이상 500자 이하여야 합니다."
            );
        }

        return normalized;
    }
}
