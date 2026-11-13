package com.team.sharedcreature.payment.application;

import com.team.sharedcreature._core.exception.BusinessException;
import com.team.sharedcreature._core.exception.ErrorCode;
import com.team.sharedcreature.admin.application.ApiIdempotencyService;
import com.team.sharedcreature.avatar.infrastructure.OwnedItemRepository;
import com.team.sharedcreature.payment.api.RefundResponse;
import com.team.sharedcreature.payment.domain.RefundRequest;
import com.team.sharedcreature.payment.infrastructure.PurchaseRepository;
import com.team.sharedcreature.payment.infrastructure.RefundRequestRepository;
import com.team.sharedcreature.auth.domain.User;
import com.team.sharedcreature.auth.infrastructure.UserRepository;
import com.team.sharedcreature.payment.api.AdminRefundResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.Set;

/**
 * 환불 요청 생성과 관리자 환불 명령을 담당합니다.
 * 외부 PG 호출의 트랜잭션 경계는 명령별로 분리합니다.
 */
@RequiredArgsConstructor
@Service
public class RefundService {

    private static final String CREATE_OPERATION = "createRefundRequest";

    private static final Set<String> ACTIVE_REFUND_STATUSES = Set.of(
            "REQUESTED",
            "CANCEL_PENDING",
            "CANCEL_UNKNOWN"
    );

    private final PurchaseRepository purchaseRepository;
    private final OwnedItemRepository ownedItemRepository;
    private final RefundRequestRepository refundRequestRepository;
    private final ApiIdempotencyService idempotencyService;
    private final Clock clock;
    private final UserRepository userRepository;
    private final RefundLockService refundLockService;
    private final RefundQueryService refundQueryService;

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
        var claim = idempotencyService.claim(
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
        var purchase = purchaseRepository.findByIdForUpdate(purchaseId)
                .orElseThrow(() ->
                        new BusinessException(ErrorCode.RESOURCE_NOT_FOUND)
                );

        if (!userId.equals(purchase.getUser().getId())) {
            throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND);
        }

        // 잠금 2: 구매에 연결된 보유 아이템.
        var item = ownedItemRepository.findByPurchaseIdForUpdate(purchaseId)
                .orElseThrow(() ->
                        new BusinessException(ErrorCode.RESOURCE_NOT_FOUND)
                );

        // 잠금 3: 같은 구매의 환불 이력.
        var refunds = refundRequestRepository
                .findAllByPurchaseIdForUpdate(purchaseId);

        if (!"PAID".equals(purchase.getStatus())) {
            throw new BusinessException(
                    ErrorCode.RESOURCE_NOT_FOUND,
                    "환불 가능한 결제 완료 구매를 찾을 수 없습니다."
            );
        }

        // 상태와 최초 사용 이력을 함께 확인합니다.
        if (item.getUsedAt() != null || "OWNED_USED".equals(item.getStatus())) {
            throw new BusinessException(ErrorCode.REFUND_NOT_ALLOWED_USED_ITEM);
        }

        if (!"OWNED_UNUSED".equals(item.getStatus())
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
        var refund = refundRequestRepository.save(
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
        var locked = refundLockService.lock(requestId);

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

        if (!"ACTIVE".equals(admin.getStatus())
                || !"SUPER_ADMIN".equals(admin.getGlobalRole())) {
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
