package com.team.sharedcreature.payment.application;

import com.team.sharedcreature._core.api.ErrorResponseFactory;
import com.team.sharedcreature._core.exception.BusinessException;
import com.team.sharedcreature._core.exception.ErrorCode;
import com.team.sharedcreature.admin.application.ApiIdempotencyService;
import com.team.sharedcreature.auth.infrastructure.UserRepository;
import com.team.sharedcreature.auth.domain.GlobalRole;
import com.team.sharedcreature.auth.domain.UserStatus;
import com.team.sharedcreature.payment.application
        .RefundApprovalTransactionService.CommandResponse;
import com.team.sharedcreature.payment.application
        .RefundRecoveryPreparationService.RecoveryCommand;
import com.team.sharedcreature.payment.api.AdminRefundResponse;
import com.team.sharedcreature.payment.infrastructure.RefundIdempotencyRepository;
import com.team.sharedcreature.payment.domain.RefundStatus;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.List;
import java.util.Optional;

/**
 * 관리자 수동 복구의 권한·멱등 기록·조회 준비를 함께 확정합니다.
 * PG 최신 조회는 이 트랜잭션이 Commit된 뒤 수행합니다.
 */
@RequiredArgsConstructor
@Service
public class RefundReconciliationPreparationService {

    private final UserRepository userRepository;
    private final ApiIdempotencyService idempotencyService;
    private final RefundIdempotencyRepository refundIdempotencyRepository;
    private final RefundRecoveryPreparationService preparationService;
    private final RefundQueryService refundQueryService;
    private final ErrorResponseFactory responseFactory;
    private final Clock clock;

    /**
     * 동일 요청은 저장된 응답을 재사용하고 신규 요청만 조회를 준비합니다.
     *
     * @param adminUserId 요청한 관리자 ID
     * @param requestId 환불 요청 ID
     * @param idempotencyKey REST 요청 멱등 UUID
     * @param traceId 현재 요청의 추적 ID
     * @return 조회 명령 또는 즉시 반환할 응답
     */
    @Transactional(
            propagation = Propagation.REQUIRES_NEW,
            isolation = Isolation.READ_COMMITTED
    )
    public Preparation prepare(
            Long adminUserId,
            Long requestId,
            String idempotencyKey,
            String traceId
    ) {
        if (adminUserId == null) {
            throw new BusinessException(ErrorCode.ROLE_FORBIDDEN);
        }

        // 활성 상태의 전체 관리자만 환불 상태의 수동 복구를 요청할 수 있습니다.
        boolean allowed = userRepository.findById(adminUserId)
                .filter(user ->
                        user.getStatus() == UserStatus.ACTIVE
                                && user.getGlobalRole() == GlobalRole.SUPER_ADMIN
                )
                .isPresent();

        if (!allowed) {
            throw new BusinessException(ErrorCode.ROLE_FORBIDDEN);
        }

        if (requestId == null || requestId < 1) {
            throw new BusinessException(
                    ErrorCode.INVALID_FIELD_VALUE,
                    "환불 요청 ID는 1 이상이어야 합니다."
            );
        }

        if (idempotencyKey == null || !idempotencyKey.matches(
                "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-"
                        + "[0-9a-fA-F]{4}-[0-9a-fA-F]{12}"
        )) {
            throw new BusinessException(
                    ErrorCode.INVALID_FIELD_VALUE,
                    "Idempotency-Key는 표준 UUID여야 합니다."
            );
        }

        ApiIdempotencyService.Claim<CommandResponse> claim = idempotencyService.claim(
                adminUserId,
                "reconcileRefund",
                idempotencyKey,
                "requestId=" + requestId,
                CommandResponse.class
        );

        // 완료된 동일 요청은 최신 PG 조회도 반복하지 않습니다.
        if (claim.replayed()) {
            return new Preparation(null, null, claim.response());
        }

        // 멱등 기록과 복구 선점·조회 이력을 같은 트랜잭션에 저장합니다.
        Optional<RecoveryCommand> prepared =
                preparationService.prepareManualWithinTransaction(requestId);

        if (prepared.isEmpty()) {
            // 이미 확정됐거나 다른 호출이 진행 중이면 현재 상태를 반환합니다.
            AdminRefundResponse data = refundQueryService.getAdminRefund(requestId);

            CommandResponse response;

        if (data.status() == RefundStatus.CANCEL_UNKNOWN) {
                response = new CommandResponse(
                        409,
                        null,
                        responseFactory.create(
                                ErrorCode.PG_CANCEL_RESULT_UNKNOWN,
                                null,
                                List.of(),
                                traceId
                        )
                );
            } else {
                response = new CommandResponse(200, data, null);
            }

            // 조회를 새로 시작하지 않는 응답도 최초 결과로 저장합니다.
            idempotencyService.complete(
                    claim,
                    response.httpStatus(),
                    "REFUND_REQUEST",
                    requestId,
                    response
            );

            return new Preparation(null, null, response);
        }

        int bound = refundIdempotencyRepository.bindProcessingRefund(
                claim.record().getId(),
                requestId,
                clock.instant()
        );

        if (bound != 1) {
            throw new IllegalStateException(
                    "수동 복구 멱등 기록과 환불 요청을 연결하지 못했습니다."
            );
        }

        // PG 조회 후 결과 저장 단계에서 이 멱등 기록을 완료합니다.
        return new Preparation(
                prepared.get(),
                claim.record().getId(),
                null
        );
    }

    /**
     * 신규 조회에는 command와 멱등 기록 ID가 있습니다.
     * 즉시 응답 또는 재요청에는 response가 있습니다.
     */
    public record Preparation(
            RecoveryCommand command,
            Long idempotencyRecordId,
            CommandResponse response
    ) {
    }
}
