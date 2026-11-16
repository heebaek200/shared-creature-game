package com.team.sharedcreature.payment.application;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** 실제 복구 Service의 집계·단건 장애 격리·Heartbeat 동작을 확인합니다. */
class RefundReconciliationServiceTest {
    private final RefundRecoveryPreparationService preparation = mock(RefundRecoveryPreparationService.class);
    private final RefundLookupGateway lookup = mock(RefundLookupGateway.class);
    private final RefundResultTransactionService results = mock(RefundResultTransactionService.class);
    private final RefundReconciliationService service = new RefundReconciliationService(
            preparation, lookup, results, mock(RefundReconciliationPreparationService.class));

    private RefundRecoveryPreparationService.RecoveryCommand command(long id) {
        return new RefundRecoveryPreparationService.RecoveryCommand(
                id, id + 100, id + 200, id + 300, "payment-" + id,
                1000L, "KRW", "key-" + id, Instant.EPOCH);
    }

    @Test
    void 대상이_없으면_영건으로_반환하고_PG를_호출하지_않는다() {
        when(preparation.findScheduledCandidates()).thenReturn(List.of());
        var heartbeat = mock(Runnable.class);
        var summary = service.recoverStaleRefunds(heartbeat);
        assertThat(summary).isEqualTo(new RefundRecoveryWork.RecoverySummary(0, 0, 0, 0, 0, 0, 0));
        verify(heartbeat).run();
        verifyNoInteractions(lookup, results);
    }

    @Test
    void 업무결과와_기술오류를_구분하고_한건실패후에도_다음건을_처리한다() {
        when(preparation.findScheduledCandidates()).thenReturn(List.of(1L, 2L, 3L, 4L, 5L, 6L, 7L, 8L));
        when(preparation.prepareScheduled(anyLong())).thenAnswer(call -> {
            long id = call.getArgument(0);
            if (id == 8) {
                throw new IllegalStateException("DB 오류 대역");
            }
            return id == 5 ? Optional.empty() : Optional.of(command(id));
        });
        when(lookup.getPayment("payment-7")).thenThrow(new PaymentGatewayException(
                PaymentGatewayException.Kind.UNAVAILABLE, "Timeout", null));
        when(results.applyRecovery(any(), nullable(PaymentSnapshot.class))).thenAnswer(call -> {
            var cmd = (RefundRecoveryPreparationService.RecoveryCommand) call.getArgument(0);
            return switch (cmd.refundRequestId().intValue()) {
                case 1 -> Optional.of(RefundGateway.Outcome.SUCCESS);
                case 2 -> Optional.of(RefundGateway.Outcome.DEFINITIVE_FAILURE);
                case 3 -> Optional.of(RefundGateway.Outcome.PENDING);
                case 4, 7 -> Optional.of(RefundGateway.Outcome.UNKNOWN);
                case 6 -> Optional.empty();
                default -> throw new AssertionError("예상하지 않은 대상");
            };
        });
        var summary = service.recoverStaleRefunds(() -> {});
        assertThat(summary).isEqualTo(new RefundRecoveryWork.RecoverySummary(8, 1, 1, 1, 1, 2, 2));
        // PG 통신 오류도 미확정 상태 저장을 시도하며 실패로 확정하지 않습니다.
        verify(results).applyRecovery(command(7), null);
        verify(preparation).prepareScheduled(8L);
        verify(lookup, never()).getPayment("payment-5");
    }

    @Test
    void 최초_Heartbeat가_실패하면_후보조회도_시작하지_않는다() {
        var failure = new IllegalStateException("실행권 상실");
        assertThatThrownBy(() -> service.recoverStaleRefunds(() -> { throw failure; }))
                .isSameAs(failure);
        verifyNoInteractions(preparation, lookup, results);
    }

    @Test
    void 작업중_Heartbeat가_실패하면_결과저장을_막고_예외를_전달한다() {
        var executor = mock(ScheduledExecutorService.class);
        ScheduledFuture<?> future = mock(ScheduledFuture.class);
        var callback = new AtomicReference<Runnable>();
        // 실패 시점을 직접 제어하여 스레드 실행 순서에 의존하지 않습니다.
        doAnswer(call -> {
            callback.set(call.getArgument(0));
            return future;
        }).when(executor).scheduleWithFixedDelay(any(Runnable.class), eq(30L), eq(30L), eq(TimeUnit.SECONDS));
        when(preparation.findScheduledCandidates()).thenReturn(List.of(1L));
        when(preparation.prepareScheduled(1L)).thenReturn(Optional.of(command(1)));
        when(lookup.getPayment("payment-1")).thenAnswer(call -> {
            callback.get().run();
            return null;
        });
        var calls = new AtomicInteger();
        var failure = new IllegalStateException("Lease 갱신 실패");
        try (var mockedExecutors = mockStatic(Executors.class)) {
            mockedExecutors.when(Executors::newSingleThreadScheduledExecutor).thenReturn(executor);
            assertThatThrownBy(() -> service.recoverStaleRefunds(() -> {
                if (calls.incrementAndGet() > 1) {
                    throw failure;
                }
            })).isInstanceOf(IllegalStateException.class).hasCause(failure);
        }
        verifyNoInteractions(results);
        verify(future).cancel(true);
        verify(executor).shutdownNow();
    }

    @Test
    @Timeout(40)
    void 실제_30초_주기로_PG대기중에도_Heartbeat를_호출한다() {
        when(preparation.findScheduledCandidates()).thenReturn(List.of(1L));
        when(preparation.prepareScheduled(1L)).thenReturn(Optional.of(command(1)));
        var renewed = new CountDownLatch(1);
        var calls = new AtomicInteger();
        when(lookup.getPayment("payment-1")).thenAnswer(call -> {
            // 호출 건수가 늘지 않는 PG 대기 상황을 재현합니다.
            assertThat(renewed.await(35, TimeUnit.SECONDS)).isTrue();
            return null;
        });
        when(results.applyRecovery(command(1), null)).thenReturn(Optional.of(RefundGateway.Outcome.UNKNOWN));
        var summary = service.recoverStaleRefunds(() -> {
            if (calls.incrementAndGet() > 1) {
                renewed.countDown();
            }
        });
        assertThat(calls.get()).isGreaterThanOrEqualTo(2);
        assertThat(summary).isEqualTo(new RefundRecoveryWork.RecoverySummary(1, 0, 0, 0, 1, 0, 0));
    }
}
