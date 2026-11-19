package com.team.sharedcreature.payment.api;

import com.team.sharedcreature._core.api.ApiResponse;
import com.team.sharedcreature._core.exception.BusinessException;
import com.team.sharedcreature._core.exception.ErrorCode;
import com.team.sharedcreature._core.security.LoginUser;
import com.team.sharedcreature.payment.application.RefundQueryService;
import com.team.sharedcreature.payment.application.RefundService;
import com.team.sharedcreature.payment.application.RefundReconciliationService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 전체 관리자의 환불 조회와 승인·거절 요청을 처리합니다.
 * 관리자 접근 권한은 기존 Security 설정에서 검사합니다.
 */
@Validated
@RequiredArgsConstructor
@RestController
@RequestMapping("/api/v1/admin/refund-requests")
public class AdminRefundController {

    private static final String UUID_PATTERN =
            "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}"
                    + "-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$";

    private final RefundService refundService;
    private final RefundQueryService refundQueryService;
    private final RefundReconciliationService reconciliationService;

    /**
     * 전체 사용자의 환불 요청을 페이지 단위로 조회합니다.
     * 상태 필터를 생략하면 모든 상태를 조회합니다.
     */
    @GetMapping
    public ApiResponse<List<RefundResponse>> listAdminRefundRequests(
            @RequestParam(name = "page", required = false) String page,
            @RequestParam(name = "size", required = false) String size,
            @RequestParam(name = "status", required = false) String status
    ) {
        int pageNumber = parseInteger(
                page, 0, 0, Integer.MAX_VALUE, "page"
        );
        int pageSize = parseInteger(size, 20, 1, 50, "size");

        return ApiResponse.page(
                refundQueryService.listAdminRefunds(
                        pageNumber,
                        pageSize,
                        RefundStatusFilter.parse(status)
                )
        );
    }

    /**
     * 환불 요청과 구매·아이템·최근 취소 거래 정보를 조회합니다.
     */
    @GetMapping("/{requestId}")
    public ApiResponse<AdminRefundResponse> getAdminRefundRequest(
            @PathVariable(name = "requestId") @Min(1) Long requestId
    ) {
        return ApiResponse.success(
                refundQueryService.getAdminRefund(requestId)
        );
    }

    /**
     * 미사용 아이템의 환불을 승인하고 PG 전체 취소를 요청합니다.
     * 결과 저장 트랜잭션이 Commit된 다음 HTTP 응답을 생성합니다.
     */
    @PostMapping("/{requestId}/approvals")
    public ResponseEntity<?> approveRefund(
            @AuthenticationPrincipal LoginUser loginUser,
            @PathVariable(name = "requestId") @Min(1) Long requestId,
            @RequestHeader(name = "Idempotency-Key")
            @Pattern(regexp = UUID_PATTERN) String idempotencyKey,
            @Valid @RequestBody RefundDecisionRequest body,
            HttpServletRequest request
    ) {
        var result = refundService.approveRefund(
                loginUser.id(),
                requestId,
                body.decisionReason(),
                idempotencyKey,
                traceId(request)
        );

        // 오류도 예외를 던지지 않고 저장된 HTTP 응답으로 전달합니다.
        if (result.error() != null) {
            return ResponseEntity.status(result.httpStatus())
                    .body(result.error());
        }

        // 내부 CommandResponse는 노출하지 않고 공통 성공 형식을 사용합니다.
        return ResponseEntity.status(result.httpStatus())
                .body(ApiResponse.success(result.data()));
    }

    /**
     * 관리자 판단 사유를 기록하고 미심사 환불 요청을 거절합니다.
     * 거절에는 PG 취소 호출과 멱등 키가 필요하지 않습니다.
     */
    @PostMapping("/{requestId}/rejections")
    public ApiResponse<AdminRefundResponse> rejectRefund(
            @AuthenticationPrincipal LoginUser loginUser,
            @PathVariable(name = "requestId") @Min(1) Long requestId,
            @Valid @RequestBody RefundDecisionRequest body
    ) {
        return ApiResponse.success(
                refundService.rejectRefund(
                        loginUser.id(),
                        requestId,
                        body.decisionReason()
                )
        );
    }

    /**
     * 미확정 환불의 최신 PG 상태를 조회하고 결과를 복구합니다.
     * 취소 요청을 재전송하지 않으며 요청 본문은 사용하지 않습니다.
     */
    @PostMapping("/{requestId}/reconciliations")
    public ResponseEntity<?> reconcileRefund(
            @AuthenticationPrincipal LoginUser loginUser,
            @PathVariable(name = "requestId") @Min(1) Long requestId,
            @RequestHeader(name = "Idempotency-Key")
            @Pattern(regexp = UUID_PATTERN) String idempotencyKey,
            HttpServletRequest request
    ) {
        var result = reconciliationService.reconcileRefund(
                loginUser.id(),
                requestId,
                idempotencyKey,
                traceId(request)
        );

        // 상태와 최초 응답이 Commit된 뒤 저장된 오류를 전달합니다.
        if (result.error() != null) {
            return ResponseEntity.status(result.httpStatus())
                    .body(result.error());
        }

        // 내부 처리용 객체 대신 공통 성공 Envelope을 반환합니다.
        return ResponseEntity.status(result.httpStatus())
                .body(ApiResponse.success(result.data()));
    }

    /** 기존 요청 필터가 부여한 추적 ID를 가져옵니다. */
    private String traceId(HttpServletRequest request) {
        Object value = request.getAttribute("requestId");
        return value == null ? "unavailable" : value.toString();
    }

    /**
     * 페이지 입력의 정수 형식과 범위를 검사합니다.
     * 생략한 값에만 기본값을 적용합니다.
     */
    private int parseInteger(
            String value,
            int defaultValue,
            int min,
            int max,
            String fieldName
    ) {
        if (value == null) {
            return defaultValue;
        }

        int parsed;

        try {
            parsed = Integer.parseInt(value);
        } catch (NumberFormatException exception) {
            throw new BusinessException(
                    ErrorCode.INVALID_FIELD_VALUE,
                    fieldName + "는 정수로 입력해 주세요."
            );
        }

        if (parsed < min || parsed > max) {
            throw new BusinessException(
                    ErrorCode.INVALID_FIELD_VALUE,
                    fieldName + "의 허용 범위를 확인해 주세요."
            );
        }

        return parsed;
    }
}
