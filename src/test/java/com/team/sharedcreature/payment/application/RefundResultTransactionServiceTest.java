package com.team.sharedcreature.payment.application;

import com.team.sharedcreature._core.api.ErrorResponseFactory;
import com.team.sharedcreature.admin.domain.ApiIdempotencyRecord;
import com.team.sharedcreature.auth.domain.User;
import com.team.sharedcreature.avatar.domain.OwnedItem;
import com.team.sharedcreature.avatar.domain.OwnedItemStatus;
import com.team.sharedcreature.payment.api.AdminRefundResponse;
import com.team.sharedcreature.payment.domain.*;
import com.team.sharedcreature.payment.infrastructure.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.BeanUtils;
import org.springframework.test.util.ReflectionTestUtils;
import tools.jackson.databind.json.JsonMapper;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** 결과별 업무 변경을 검증합니다. 실제 DB Commit·Rollback은 통합 테스트 대상입니다. */
class RefundResultTransactionServiceTest {
    private static final Instant NOW = Instant.parse("2026-11-13T00:05:00Z");
    private final RefundLockService locks = mock(RefundLockService.class);
    private final PgApiAttemptRepository attempts = mock(PgApiAttemptRepository.class);
    private final PaymentTransactionRepository transactions = mock(PaymentTransactionRepository.class);
    private final RefundQueryService queries = mock(RefundQueryService.class);
    private final RefundIdempotencyRepository idempotency = mock(RefundIdempotencyRepository.class);
    private final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
    private final JsonMapper mapper = JsonMapper.builder().build();
    private final RefundResultTransactionService service = new RefundResultTransactionService(
            locks, attempts, transactions, queries, mapper, clock, idempotency,
            new ErrorResponseFactory(clock), new RefundRecoveryResultResolver("store"));
    private Purchase purchase;
    private OwnedItem item;
    private RefundRequest refund;
    private PgApiAttempt attempt;
    private ApiIdempotencyRecord record;
    private RefundApprovalTransactionService.CancellationCommand command;

    @BeforeEach
    void 준비() {
        var user = BeanUtils.instantiateClass(User.class);
        set(user, "id", 7L);
        purchase = BeanUtils.instantiateClass(Purchase.class);
        set(purchase, "id", 2L);
        set(purchase, "user", user);
        set(purchase, "orderNumber", "payment");
        set(purchase, "priceKrwSnapshot", 1000L);
        set(purchase, "currency", "KRW");
        set(purchase, "status", "PAID");
        item = OwnedItem.grant(purchase, NOW.minusSeconds(600));
        refund = RefundRequest.create(purchase, user, "요청", NOW.minusSeconds(600));
        set(refund, "id", 1L);
        refund.claimCancellation(user, "승인", NOW.minusSeconds(300));
        attempt = PgApiAttempt.prepareCancellation(refund, 1, NOW.minusSeconds(300));
        set(attempt, "id", 3L);
        record = BeanUtils.instantiateClass(ApiIdempotencyRecord.class);
        set(record, "operation", "approveRefund");
        set(record, "status", "PROCESSING");
        command = new RefundApprovalTransactionService.CancellationCommand(
                1L, 2L, 3L, "payment", 1000L, "KRW", "승인", attempt.getIdempotencyKey(), 4L);
        when(locks.lock(1L)).thenReturn(new RefundLockService.LockedRefund(purchase, item, refund));
        when(attempts.findByIdAndRefundRequestIdForUpdate(3L, 1L)).thenReturn(Optional.of(attempt));
        when(idempotency.findForUpdateByIdAndRefundRequestId(4L, 1L)).thenReturn(Optional.of(record));
        when(transactions.save(any(PaymentTransaction.class))).thenAnswer(call -> call.getArgument(0));
        when(queries.getAdminRefund(1L)).thenAnswer(call -> new AdminRefundResponse(
                1L, 2L, 7L, "요청", "승인", refund.getStatus(), NOW.minusSeconds(600),
                NOW.minusSeconds(300), refund.getCompletedAt(), 7L,
                new AdminRefundResponse.PurchaseInfo("payment", "아이템", 1000L, purchase.getStatus()),
                new AdminRefundResponse.OwnedItemInfo(8L, item.getStatus().name(), item.getUsedAt()), null));
    }

    @ParameterizedTest
    @EnumSource(RefundGateway.Outcome.class)
    void PG결과별_상태와_회수여부와_최초응답을_저장한다(RefundGateway.Outcome outcome) {
        var result = service.apply(command, pgResult(outcome), "trace");
        String expected = switch (outcome) {
            case SUCCESS -> "COMPLETED";
            case DEFINITIVE_FAILURE -> "FAILED";
            case PENDING -> "CANCEL_PENDING";
            case UNKNOWN -> "CANCEL_UNKNOWN";
        };
        assertThat(refund.getStatus()).isEqualTo(expected);
        assertThat(purchase.getStatus()).isEqualTo(outcome == RefundGateway.Outcome.SUCCESS ? "CANCELLED" : "PAID");
        assertThat(item.getStatus()).isEqualTo(outcome == RefundGateway.Outcome.SUCCESS
                ? OwnedItemStatus.REVOKED
                : OwnedItemStatus.OWNED_UNUSED);
        assertThat(item.getRevokedAt()).isEqualTo(outcome == RefundGateway.Outcome.SUCCESS ? NOW : null);
        assertThat(item.getUsedAt()).isNull();
        assertThat(attempt.getResultType()).isEqualTo(outcome.name());
        assertThat(attempt.getCompletedAt()).isEqualTo(NOW);
        assertThat(record.getStatus()).isEqualTo("COMPLETED");
        assertThat(result.httpStatus()).isEqualTo(outcome == RefundGateway.Outcome.UNKNOWN ? 409 : 200);
        assertThat(record.getHttpStatus()).isEqualTo(result.httpStatus());
        if (outcome == RefundGateway.Outcome.UNKNOWN) {
            assertThat(result.error().code()).isEqualTo("PG_CANCEL_RESULT_UNKNOWN");
            assertThat(result.data()).isNull();
        }
    }

    @Test
    void 같은_성공결과를_재반영해도_거래를_추가하거나_다시_회수하지_않는다() {
        service.apply(command, pgResult(RefundGateway.Outcome.SUCCESS), "first");
        String snapshot = record.getResponseSnapshot();
        Instant revokedAt = item.getRevokedAt();
        var replay = service.apply(command, pgResult(RefundGateway.Outcome.UNKNOWN), "second");
        assertThat(replay.httpStatus()).isEqualTo(200);
        assertThat(replay.data().status()).isEqualTo("COMPLETED");
        assertThat(item.getRevokedAt()).isEqualTo(revokedAt);
        assertThat(record.getResponseSnapshot()).isEqualTo(snapshot);
        verify(transactions, times(1)).save(any(PaymentTransaction.class));
        verify(queries, times(1)).getAdminRefund(1L);
    }

    @Test
    void 최초_409응답은_나중에_환불이_완료돼도_그대로_재사용한다() {
        service.apply(command, pgResult(RefundGateway.Outcome.UNKNOWN), "first");
        // 후속 정합화로 환불이 완료된 상황에서도 최초 멱등 응답은 보존합니다.
        refund.completeCancellation(NOW, NOW);
        var replay = service.apply(command, pgResult(RefundGateway.Outcome.SUCCESS), "second");
        assertThat(replay.httpStatus()).isEqualTo(409);
        assertThat(replay.error().traceId()).isEqualTo("first");
        assertThat(replay.error().code()).isEqualTo("PG_CANCEL_RESULT_UNKNOWN");
        verify(transactions, times(1)).save(any(PaymentTransaction.class));
    }

    private RefundGateway.CancellationResult pgResult(RefundGateway.Outcome outcome) {
        return new RefundGateway.CancellationResult(outcome, "payment", "cancel-1", 1000L,
                outcome == RefundGateway.Outcome.SUCCESS ? NOW.minusSeconds(1) : null, 200, outcome.name());
    }

    private static void set(Object target, String field, Object value) {
        ReflectionTestUtils.setField(target, field, value);
    }
}
