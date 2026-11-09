package com.team.sharedcreature.payment.api;

import com.team.sharedcreature._core.api.ApiResponse;
import com.team.sharedcreature._core.security.LoginUser;
import com.team.sharedcreature.payment.application.PaymentReconciliationService;
import com.team.sharedcreature.payment.application.PurchaseQueryService;
import com.team.sharedcreature.payment.application.PurchaseService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import org.springframework.data.domain.Page;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/** 구매 생성·조회와 브라우저 결제 검증 REST 요청을 처리합니다. */
@Validated
@RestController
@RequestMapping("/api/v1/purchases")
public class PaymentController {

    private static final String UUID_PATTERN =
            "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[1-5][0-9a-fA-F]{3}-[89abAB][0-9a-fA-F]{3}-[0-9a-fA-F]{12}$";

    private final PurchaseService purchaseService;
    private final PurchaseQueryService queryService;
    private final PaymentReconciliationService reconciliationService;

    /** 결제 REST 흐름에 필요한 Service를 주입받습니다. */
    public PaymentController(
            PurchaseService purchaseService,
            PurchaseQueryService queryService,
            PaymentReconciliationService reconciliationService
    ) {
        this.purchaseService = purchaseService;
        this.queryService = queryService;
        this.reconciliationService = reconciliationService;
    }

    /** 서버 상품 Snapshot으로 결제 대기 주문을 생성합니다. */
    @PostMapping
    public ResponseEntity<ApiResponse<CreatePurchaseResponse>> createPurchase(
            @AuthenticationPrincipal LoginUser loginUser,
            @RequestHeader("Idempotency-Key") @Pattern(regexp = UUID_PATTERN) String idempotencyKey,
            @Valid @RequestBody CreatePurchaseRequest request
    ) {
        CreatePurchaseResponse response = purchaseService.create(
                loginUser.id(),
                request.shopItemId(),
                idempotencyKey
        );
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.success(response));
    }

    /** PortOne 최신 상태를 조회해 결제와 아이템 지급을 확정합니다. */
    @PostMapping("/{purchaseId}/payment-verifications")
    public ApiResponse<PurchaseResponse> verifyPurchasePayment(
            @AuthenticationPrincipal LoginUser loginUser,
            @PathVariable @Min(1) Long purchaseId,
            @Valid @RequestBody VerifyPurchasePaymentRequest request
    ) {
        return ApiResponse.success(reconciliationService.reconcileForUser(
                loginUser.id(),
                purchaseId,
                request.paymentId()
        ));
    }

    /** 인증 사용자의 구매 내역을 생성 시각 내림차순으로 조회합니다. */
    @GetMapping
    public ApiResponse<List<PurchaseSummaryResponse>> listPurchases(
            @AuthenticationPrincipal LoginUser loginUser,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(50) int size,
            @RequestParam(required = false)
            @Pattern(regexp = "READY|PAID|FAILED|CANCELLED") String status
    ) {
        Page<PurchaseSummaryResponse> result = queryService.list(loginUser.id(), page, size, status);
        return ApiResponse.page(result);
    }

    /** 다른 사용자의 구매를 숨기며 단건 거래·환불 이력을 조회합니다. */
    @GetMapping("/{purchaseId}")
    public ApiResponse<PurchaseResponse> getPurchase(
            @AuthenticationPrincipal LoginUser loginUser,
            @PathVariable @Min(1) Long purchaseId
    ) {
        return ApiResponse.success(queryService.get(loginUser.id(), purchaseId));
    }
}
