package com.team.sharedcreature.payment.application;

import com.team.sharedcreature._core.exception.BusinessException;
import com.team.sharedcreature._core.exception.ErrorCode;
import com.team.sharedcreature.auth.domain.User;
import com.team.sharedcreature.payment.domain.Purchase;
import com.team.sharedcreature.payment.domain.RefundRequest;
import com.team.sharedcreature.payment.domain.RefundStatus;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

/** 사용자 환불 요청 철회의 소유권과 외부 효과 경계를 검증합니다. */
class RefundWithdrawalServiceTest {
    private static final Instant NOW = Instant.parse("2026-11-19T00:00:00Z");
    private final RefundLockService locks = mock(RefundLockService.class);
    private final RefundGateway gateway = mock(RefundGateway.class);
    private final RefundService service = new RefundService(
            null, null, null, null, Clock.fixed(NOW, ZoneOffset.UTC), null,
            locks, null, null, gateway, null
    );

    @Test
    void 본인의_요청만_철회하고_PG를_호출하지않는다() {
        RefundRequest refund = requested(1L);
        when(locks.lock(10L)).thenReturn(new RefundLockService.LockedRefund(
                refund.getPurchase(), null, refund
        ));

        var response = service.withdrawRefundRequest(1L, 10L);

        assertThat(response.status()).isEqualTo(RefundStatus.WITHDRAWN);
        assertThat(response.withdrawnAt()).isEqualTo(NOW);
        verifyNoInteractions(gateway);
    }

    @Test
    void 다른사용자에게는_요청존재를_노출하지않는다() {
        RefundRequest refund = requested(1L);
        when(locks.lock(10L)).thenReturn(new RefundLockService.LockedRefund(
                refund.getPurchase(), null, refund
        ));

        assertThatThrownBy(() -> service.withdrawRefundRequest(2L, 10L))
                .isInstanceOfSatisfying(BusinessException.class,
                        error -> assertThat(error.errorCode()).isEqualTo(ErrorCode.RESOURCE_NOT_FOUND));
        assertThat(refund.getStatus()).isEqualTo(RefundStatus.REQUESTED);
        verifyNoInteractions(gateway);
    }

    private RefundRequest requested(Long userId) {
        Purchase purchase = mock(Purchase.class);
        User requester = mock(User.class);
        when(purchase.getId()).thenReturn(20L);
        when(requester.getId()).thenReturn(userId);
        return RefundRequest.create(purchase, requester, "환불 요청", NOW.minusSeconds(60));
    }
}
