package com.team.sharedcreature.payment.api;

import com.team.sharedcreature._core.api.ApiResponse;
import com.team.sharedcreature._core.exception.BusinessException;
import com.team.sharedcreature._core.exception.ErrorCode;
import com.team.sharedcreature._core.security.LoginUser;
import com.team.sharedcreature.payment.application.RefundQueryService;
import com.team.sharedcreature.payment.application.RefundService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 로그인한 사용자의 환불 요청 생성과 목록 조회를 처리합니다.
 * 구매 소유권과 환불 가능 여부는 Service에서 검사합니다.
 */
@Validated
@RequiredArgsConstructor
@RestController
@RequestMapping("/api/v1")
public class RefundController {

    private static final String UUID_PATTERN =
            "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}"
                    + "-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$";

    private final RefundService refundService;
    private final RefundQueryService refundQueryService;

    /**
     * 본인이 구매한 미사용 아이템의 환불을 요청합니다.
     * 같은 멱등 키와 같은 내용의 재요청에는 저장된 응답을 반환합니다.
     */
    @PostMapping("/purchases/{purchaseId}/refund-requests")
    public ResponseEntity<ApiResponse<RefundResponse>> createRefundRequest(
            @AuthenticationPrincipal LoginUser loginUser,
            @PathVariable(name = "purchaseId")
            @Min(1) Long purchaseId,
            @RequestHeader(name = "Idempotency-Key")
            @Pattern(regexp = UUID_PATTERN) String idempotencyKey,
            @Valid @RequestBody CreateRefundRequest request
    ) {
        // 사용자 ID는 요청 본문이 아닌 인증 정보에서 가져옵니다.
        var response = refundService.createRefundRequest(
                loginUser.id(),
                purchaseId,
                request.reason(),
                idempotencyKey
        );

        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.success(response));
    }

    /**
     * 본인이 요청한 환불 목록을 페이지 단위로 조회합니다.
     * 상태를 생략하면 모든 상태를 조회합니다.
     */
    @GetMapping("/refund-requests")
    public ApiResponse<List<RefundResponse>> listMyRefundRequests(
            @AuthenticationPrincipal LoginUser loginUser,
            @RequestParam(name = "page", required = false) String page,
            @RequestParam(name = "size", required = false) String size,
            @RequestParam(name = "status", required = false) String status
    ) {
        // 생략한 값에만 기본값을 적용하고 빈 문자열은 거절합니다.
        int pageNumber = parseInteger(
                page, 0, 0, Integer.MAX_VALUE, "page"
        );
        int pageSize = parseInteger(size, 20, 1, 50, "size");

        var result = refundQueryService.listMyRefunds(
                loginUser.id(),
                pageNumber,
                pageSize,
                RefundStatusFilter.parse(status)
        );

        // data 목록과 meta 페이지 정보를 함께 반환합니다.
        return ApiResponse.page(result);
    }

    /**
     * 페이지 입력을 정수로 변환하고 허용 범위를 검사합니다.
     * 잘못된 값을 임의로 보정하지 않습니다.
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
