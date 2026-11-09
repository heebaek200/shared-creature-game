package com.team.sharedcreature.payment.application;

import com.team.sharedcreature._core.exception.BusinessException;
import com.team.sharedcreature._core.exception.ErrorCode;
import com.team.sharedcreature.auth.domain.User;
import com.team.sharedcreature.avatar.domain.OwnedItem;
import com.team.sharedcreature.avatar.domain.ShopItem;
import com.team.sharedcreature.avatar.infrastructure.OwnedItemRepository;
import com.team.sharedcreature.payment.api.PurchaseResponse;
import com.team.sharedcreature.payment.domain.PaymentTransaction;
import com.team.sharedcreature.payment.domain.Purchase;
import com.team.sharedcreature.payment.infrastructure.PaymentTransactionRepository;
import com.team.sharedcreature.payment.infrastructure.PortOneProperties;
import com.team.sharedcreature.payment.infrastructure.PurchaseRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class PaymentReconciliationServiceTest {

    private static final Instant NOW = Instant.parse("2026-11-09T08:00:00Z");
    private static final String PAYMENT_ID = "d4a7bba1-5180-49ef-8d61-94f18f7c7197";

    @Mock private PurchaseRepository purchaseRepository;
    @Mock private PaymentTransactionRepository transactionRepository;
    @Mock private OwnedItemRepository ownedItemRepository;
    @Mock private PaymentGateway paymentGateway;
    @Mock private PurchaseQueryService queryService;
    @Mock private TransactionTemplate transactionTemplate;
    @Mock private TransactionStatus transactionStatus;

    private PaymentReconciliationService service;
    private Purchase purchase;

    @BeforeEach
    void setUp() {
        ShopItem shopItem = mock(ShopItem.class);
        when(shopItem.getCode()).thenReturn("HAIR-01");
        when(shopItem.getName()).thenReturn("테스트 헤어");
        when(shopItem.getSlotCode()).thenReturn("HEAD");
        when(shopItem.getPriceKrw()).thenReturn(1000L);
        purchase = Purchase.ready(PAYMENT_ID, mock(User.class), shopItem, NOW.minusSeconds(60));

        PortOneProperties properties = new PortOneProperties(
                "store-test",
                "channel-test",
                "secret",
                "webhook-secret",
                "https://api.portone.io"
        );
        service = new PaymentReconciliationService(
                purchaseRepository,
                transactionRepository,
                ownedItemRepository,
                paymentGateway,
                queryService,
                properties,
                transactionTemplate,
                Clock.fixed(NOW, ZoneOffset.UTC)
        );
    }

    @Test
    void 검증된_카카오페이_테스트_결제만_PAID와_아이템_지급으로_확정한다() {
        PaymentSnapshot snapshot = paidSnapshot(1000L, "EASY_PAY", "KAKAOPAY", "TEST");
        PurchaseResponse expected = mock(PurchaseResponse.class);
        when(purchaseRepository.findByIdAndUserId(10L, 7L)).thenReturn(Optional.of(purchase));
        when(purchaseRepository.findByIdForUpdate(null)).thenReturn(Optional.of(purchase));
        when(paymentGateway.getPayment(PAYMENT_ID)).thenReturn(snapshot);
        when(transactionRepository.findByProviderAndProviderTransactionId("PORTONE", "tx-1"))
                .thenReturn(Optional.empty());
        when(transactionRepository.save(any(PaymentTransaction.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        when(ownedItemRepository.findByPurchaseId(null)).thenReturn(Optional.empty());
        when(ownedItemRepository.save(any(OwnedItem.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        when(queryService.get(7L, 10L)).thenReturn(expected);
        executeTransactionsImmediately();

        PurchaseResponse result = service.reconcileForUser(7L, 10L, PAYMENT_ID);

        assertThat(result).isSameAs(expected);
        assertThat(purchase.getStatus()).isEqualTo("PAID");
        verify(ownedItemRepository).save(any(OwnedItem.class));
        verify(transactionRepository).save(any(PaymentTransaction.class));
    }

    @Test
    void 금액이_다르면_구매와_아이템을_변경하지_않는다() {
        when(purchaseRepository.findByIdAndUserId(10L, 7L)).thenReturn(Optional.of(purchase));
        when(paymentGateway.getPayment(PAYMENT_ID))
                .thenReturn(paidSnapshot(999L, "EASY_PAY", "KAKAOPAY", "TEST"));

        assertThatThrownBy(() -> service.reconcileForUser(7L, 10L, PAYMENT_ID))
                .isInstanceOfSatisfying(BusinessException.class, exception ->
                        assertThat(exception.errorCode()).isEqualTo(ErrorCode.PAYMENT_AMOUNT_MISMATCH));
        assertThat(purchase.getStatus()).isEqualTo("READY");
        verifyNoInteractions(transactionTemplate, ownedItemRepository);
    }

    @Test
    void 카카오페이가_아니거나_실채널이면_결과_불명으로_거절한다() {
        when(purchaseRepository.findByIdAndUserId(10L, 7L)).thenReturn(Optional.of(purchase));
        when(paymentGateway.getPayment(PAYMENT_ID))
                .thenReturn(paidSnapshot(1000L, "EASY_PAY", "NAVERPAY", "LIVE"));

        assertThatThrownBy(() -> service.reconcileForUser(7L, 10L, PAYMENT_ID))
                .isInstanceOfSatisfying(BusinessException.class, exception ->
                        assertThat(exception.errorCode()).isEqualTo(ErrorCode.PAYMENT_RESULT_UNKNOWN));
        verifyNoInteractions(transactionTemplate, ownedItemRepository);
    }

    @Test
    void PortOne_통신_결과를_모르면_브라우저_API에는_PAYMENT_RESULT_UNKNOWN을_반환한다() {
        when(purchaseRepository.findByIdAndUserId(10L, 7L)).thenReturn(Optional.of(purchase));
        when(paymentGateway.getPayment(PAYMENT_ID)).thenThrow(new PaymentGatewayException(
                PaymentGatewayException.Kind.UNAVAILABLE,
                "timeout",
                null
        ));

        assertThatThrownBy(() -> service.reconcileForUser(7L, 10L, PAYMENT_ID))
                .isInstanceOfSatisfying(BusinessException.class, exception ->
                        assertThat(exception.errorCode()).isEqualTo(ErrorCode.PAYMENT_RESULT_UNKNOWN));
    }

    @Test
    void 대기_결제는_결제수단_정보가_없어도_PAYMENT_PENDING을_반환한다() {
        when(purchaseRepository.findByIdAndUserId(10L, 7L)).thenReturn(Optional.of(purchase));
        when(paymentGateway.getPayment(PAYMENT_ID)).thenReturn(new PaymentSnapshot(
                PAYMENT_ID,
                "store-test",
                null,
                PaymentSnapshot.Status.PENDING,
                1000L,
                "KRW",
                null,
                null,
                null,
                null,
                List.of()
        ));

        assertThatThrownBy(() -> service.reconcileForUser(7L, 10L, PAYMENT_ID))
                .isInstanceOfSatisfying(BusinessException.class, exception ->
                        assertThat(exception.errorCode()).isEqualTo(ErrorCode.PAYMENT_PENDING));
        verifyNoInteractions(transactionTemplate, ownedItemRepository);
    }

    @SuppressWarnings("unchecked")
    private void executeTransactionsImmediately() {
        when(transactionTemplate.execute(any())).thenAnswer(invocation -> {
            TransactionCallback<Object> callback = invocation.getArgument(0);
            return callback.doInTransaction(transactionStatus);
        });
    }

    private PaymentSnapshot paidSnapshot(long amount, String method, String provider, String channelType) {
        return new PaymentSnapshot(
                PAYMENT_ID,
                "store-test",
                "tx-1",
                PaymentSnapshot.Status.PAID,
                amount,
                "KRW",
                method,
                provider,
                channelType,
                NOW,
                List.of()
        );
    }
}
