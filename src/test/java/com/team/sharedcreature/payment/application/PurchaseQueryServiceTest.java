package com.team.sharedcreature.payment.application;

import com.team.sharedcreature._core.exception.BusinessException;
import com.team.sharedcreature._core.exception.ErrorCode;
import com.team.sharedcreature.avatar.infrastructure.OwnedItemRepository;
import com.team.sharedcreature.payment.domain.Purchase;
import com.team.sharedcreature.payment.domain.PurchaseStatus;
import com.team.sharedcreature.payment.infrastructure.PaymentTransactionRepository;
import com.team.sharedcreature.payment.infrastructure.PurchaseRepository;
import com.team.sharedcreature.payment.infrastructure.RefundRequestRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Optional;

import static com.team.sharedcreature.avatar.support.AvatarTestFixtures.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** 구매 Enum 전환 후 상태 필터와 구매·지급·환불 응답 계약을 검증합니다. */
@ExtendWith(MockitoExtension.class)
class PurchaseQueryServiceTest {
    @Mock private PurchaseRepository purchases;
    @Mock private OwnedItemRepository items;
    @Mock private PaymentTransactionRepository transactions;
    @Mock private RefundRequestRepository refunds;
    private PurchaseQueryService service;

    @BeforeEach
    void 준비() {
        service = new PurchaseQueryService(purchases, items, transactions, refunds);
    }

    @ParameterizedTest
    @EnumSource(PurchaseStatus.class)
    void 목록은_Enum필터로_조회하고_상태문자열과_null정보를_유지한다(PurchaseStatus status) {
        var purchase = purchase(status);
        when(purchases.findByUserIdAndStatus(eq(1L), eq(status), any()))
                .thenReturn(new PageImpl<>(List.of(purchase)));
        when(items.findByPurchaseId(20L)).thenReturn(Optional.empty());
        when(refunds.findFirstByPurchaseIdOrderByRequestedAtDesc(20L)).thenReturn(Optional.empty());

        var result = service.list(1L, 0, 20, status.name());

        assertThat(result.getContent().getFirst().status()).isEqualTo(status.name());
        assertThat(result.getContent().getFirst().ownedItemStatus()).isNull();
        assertThat(result.getContent().getFirst().latestRefundStatus()).isNull();
        assertThat(result.getTotalElements()).isEqualTo(1);
        verify(purchases, never()).findByUserId(anyLong(), any());
    }

    @ParameterizedTest
    @EnumSource(PurchaseStatus.class)
    void 상세도_기존_상태문자열과_빈목록을_반환한다(PurchaseStatus status) {
        when(purchases.findByIdAndUserId(20L, 1L)).thenReturn(Optional.of(purchase(status)));
        when(items.findByPurchaseId(20L)).thenReturn(Optional.empty());
        when(refunds.findByPurchaseIdOrderByRequestedAtAsc(20L)).thenReturn(List.of());
        when(transactions.findByPurchaseIdOrderByCreatedAtAsc(20L)).thenReturn(List.of());

        var result = service.get(1L, 20L);

        assertThat(result.status()).isEqualTo(status.name());
        assertThat(result.ownedItemStatus()).isNull();
        assertThat(result.latestRefundStatus()).isNull();
        assertThat(result.transactions()).isEmpty();
        assertThat(result.refundRequests()).isEmpty();
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"", " ", "\t"})
    void 서비스의_미지정과_공백필터는_기존대로_전체조회한다(String status) {
        when(purchases.findByUserId(eq(1L), any())).thenReturn(Page.empty());

        assertThat(service.list(1L, 0, 20, status)).isEmpty();
        verify(purchases, never()).findByUserIdAndStatus(anyLong(), any(), any());
    }

    @ParameterizedTest
    @ValueSource(strings = {"paid", " PAID", "PAID ", "UNKNOWN", "CANCEL_PENDING"})
    void 잘못된_상태는_구매조회전에_필드오류로_거절한다(String status) {
        assertThatThrownBy(() -> service.list(1L, 0, 20, status))
                .isInstanceOfSatisfying(BusinessException.class, exception ->
                        assertThat(exception.errorCode()).isEqualTo(ErrorCode.INVALID_FIELD_VALUE));
        verifyNoInteractions(purchases, items, transactions, refunds);
    }

    @Test
    void 다른_사용자의_구매는_기존_404오류로_거절한다() {
        when(purchases.findByIdAndUserId(20L, 1L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.get(1L, 20L))
                .isInstanceOfSatisfying(BusinessException.class, exception ->
                        assertThat(exception.errorCode()).isEqualTo(ErrorCode.RESOURCE_NOT_FOUND));
        verifyNoInteractions(items, transactions, refunds);
    }

    /** 실제 도메인 전환으로 각 결제 상태의 구매를 준비합니다. */
    private Purchase purchase(PurchaseStatus status) {
        var item = unusedItem(10L, 1L, "HEAD");
        var purchase = Purchase.ready("test-order", item.getUser(), item.getShopItem(), CREATED_AT);
        ReflectionTestUtils.setField(purchase, "id", 20L);
        switch (status) {
            case READY -> { }
            case PAID -> purchase.markPaid(NOW);
            case FAILED -> purchase.markFailed(NOW);
            case CANCELLED -> {
                purchase.markPaid(NOW);
                purchase.markCancelledAfterRefund(NOW);
            }
        }
        return purchase;
    }
}
