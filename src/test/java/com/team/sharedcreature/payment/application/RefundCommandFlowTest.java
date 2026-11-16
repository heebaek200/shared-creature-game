package com.team.sharedcreature.payment.application;

import com.team.sharedcreature._core.api.ErrorResponse;
import com.team.sharedcreature._core.exception.BusinessException;
import com.team.sharedcreature._core.exception.ErrorCode;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** 승인·수동 복구 흐름의 외부 호출 횟수와 장애 전달을 검증합니다. */
class RefundCommandFlowTest {
    private final RefundApprovalTransactionService approvals = mock(RefundApprovalTransactionService.class);
    private final RefundGateway cancelGateway = mock(RefundGateway.class);
    private final RefundResultTransactionService results = mock(RefundResultTransactionService.class);
    private final RefundRecoveryPreparationService recoveries = mock(RefundRecoveryPreparationService.class);
    private final RefundLookupGateway lookup = mock(RefundLookupGateway.class);
    private final RefundReconciliationPreparationService manual = mock(RefundReconciliationPreparationService.class);
    private final RefundService refunds = new RefundService(
            null, null, null, null, null, null, null, null, approvals, cancelGateway, results);
    private final RefundReconciliationService reconciliation =
            new RefundReconciliationService(recoveries, lookup, results, manual);
    private final RefundApprovalTransactionService.CommandResponse response =
            new RefundApprovalTransactionService.CommandResponse(200, null, null);

    private RefundApprovalTransactionService.CancellationCommand cancelCommand() {
        return new RefundApprovalTransactionService.CancellationCommand(
                1L, 2L, 3L, "payment", 1000L, "KRW", "승인", "pg-key", 4L);
    }

    private RefundRecoveryPreparationService.RecoveryCommand recoveryCommand() {
        return new RefundRecoveryPreparationService.RecoveryCommand(
                1L, 2L, 3L, 5L, "payment", 1000L, "KRW", "pg-key", Instant.EPOCH);
    }

    @Test
    void 승인_멱등_재요청은_PG와_결과저장을_호출하지_않는다() {
        when(approvals.prepare(9L, 1L, "승인", "key"))
                .thenReturn(new RefundApprovalTransactionService.Preparation(null, response));
        assertThat(refunds.approveRefund(9L, 1L, "승인", "key", "trace")).isSameAs(response);
        verifyNoInteractions(cancelGateway, results);
    }

    @Test
    void 신규_승인은_준비후_PG를_한번_호출하고_결과를_저장한다() {
        var command = cancelCommand();
        var pgResult = new RefundGateway.CancellationResult(
                RefundGateway.Outcome.PENDING, "payment", "cancel", 1000L, null, 200, "REQUESTED");
        when(approvals.prepare(9L, 1L, "승인", "key"))
                .thenReturn(new RefundApprovalTransactionService.Preparation(command, null));
        when(cancelGateway.cancelFully("payment", "승인", "pg-key")).thenReturn(pgResult);
        when(results.apply(command, pgResult, "trace")).thenReturn(response);
        assertThat(refunds.approveRefund(9L, 1L, "승인", "key", "trace")).isSameAs(response);
        var order = inOrder(approvals, cancelGateway, results);
        order.verify(approvals).prepare(9L, 1L, "승인", "key");
        order.verify(cancelGateway).cancelFully("payment", "승인", "pg-key");
        order.verify(results).apply(command, pgResult, "trace");
        verifyNoMoreInteractions(cancelGateway);
    }

    @Test
    void 승인_준비가_충돌하면_PG를_호출하지_않는다() {
        when(approvals.prepare(anyLong(), anyLong(), anyString(), anyString()))
                .thenThrow(new BusinessException(ErrorCode.CONCURRENT_MODIFICATION));
        assertThatThrownBy(() -> refunds.approveRefund(9L, 1L, "승인", "key", "trace"))
                .isInstanceOf(BusinessException.class);
        verifyNoInteractions(cancelGateway, results);
    }

    @Test
    void 수동복구_재요청은_최신조회도_반복하지_않는다() {
        when(manual.prepare(9L, 1L, "key", "trace"))
                .thenReturn(new RefundReconciliationPreparationService.Preparation(null, null, response));
        assertThat(reconciliation.reconcileRefund(9L, 1L, "key", "trace")).isSameAs(response);
        verifyNoInteractions(lookup, results);
    }

    @Test
    void 수동조회_통신장애는_null결과를_저장하고_409응답을_반환한다() {
        var command = recoveryCommand();
        var unknownResponse = new RefundApprovalTransactionService.CommandResponse(409, null,
                new ErrorResponse("PG_CANCEL_RESULT_UNKNOWN", "결과 불명", java.util.List.of(), "trace", Instant.EPOCH));
        when(manual.prepare(9L, 1L, "key", "trace"))
                .thenReturn(new RefundReconciliationPreparationService.Preparation(command, 4L, null));
        when(lookup.getPayment("payment")).thenThrow(new PaymentGatewayException(
                PaymentGatewayException.Kind.UNAVAILABLE, "Timeout", null));
        when(results.applyManualRecovery(command, 4L, null, "trace")).thenReturn(unknownResponse);
        assertThat(reconciliation.reconcileRefund(9L, 1L, "key", "trace")).isSameAs(unknownResponse);
        verify(lookup, times(1)).getPayment("payment");
        verify(results).applyManualRecovery(command, 4L, null, "trace");
        verifyNoInteractions(cancelGateway);
    }
}
