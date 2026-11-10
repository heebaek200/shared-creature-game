package com.team.sharedcreature.payment.application;

import com.team.sharedcreature.admin.application.ApiIdempotencyService;
import com.team.sharedcreature.admin.domain.ApiIdempotencyRecord;
import com.team.sharedcreature.auth.domain.User;
import com.team.sharedcreature.auth.infrastructure.UserRepository;
import com.team.sharedcreature.avatar.domain.ShopItem;
import com.team.sharedcreature.avatar.infrastructure.ShopItemRepository;
import com.team.sharedcreature.payment.api.CreatePurchaseResponse;
import com.team.sharedcreature.payment.domain.Purchase;
import com.team.sharedcreature.payment.infrastructure.PortOneProperties;
import com.team.sharedcreature.payment.infrastructure.PurchaseRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class PurchaseServiceTest {

    @Mock private PurchaseRepository purchaseRepository;
    @Mock private ShopItemRepository shopItemRepository;
    @Mock private UserRepository userRepository;
    @Mock private ApiIdempotencyService idempotencyService;

    private PurchaseService service;

    @BeforeEach
    void setUp() {
        service = new PurchaseService(
                purchaseRepository,
                shopItemRepository,
                userRepository,
                idempotencyService,
                new PortOneProperties("store-test", "channel-test", "secret", "webhook", "https://api.portone.io"),
                Clock.fixed(Instant.parse("2026-11-09T08:00:00Z"), ZoneOffset.UTC)
        );
    }

    @Test
    void 서버_상품_가격으로_READY_주문과_공개_결제설정을_생성한다() {
        ShopItem item = mock(ShopItem.class);
        User user = mock(User.class);
        when(item.getId()).thenReturn(101L);
        when(item.getCode()).thenReturn("HEAD-01");
        when(item.getName()).thenReturn("새싹 모자");
        when(item.getSlotCode()).thenReturn("HEAD");
        when(item.getPriceKrw()).thenReturn(1500L);
        when(shopItemRepository.findByIdAndStatus(101L, "ACTIVE")).thenReturn(Optional.of(item));
        when(userRepository.getReferenceById(7L)).thenReturn(user);
        when(idempotencyService.claim(eq(7L), eq("CREATE_PURCHASE"), anyString(), eq("shopItemId=101"),
                eq(CreatePurchaseResponse.class)))
                .thenReturn(new ApiIdempotencyService.Claim<>(mock(ApiIdempotencyRecord.class), false, null));
        when(purchaseRepository.save(any(Purchase.class))).thenAnswer(invocation -> invocation.getArgument(0));

        CreatePurchaseResponse response = service.create(7L, 101L, UUID.randomUUID().toString());

        assertThat(response.totalAmount()).isEqualTo(1500L);
        assertThat(response.currency()).isEqualTo("KRW");
        assertThat(response.payment().storeId()).isEqualTo("store-test");
        assertThat(response.payment().channelKey()).isEqualTo("channel-test");
        assertThat(response.payment().easyPay().easyPayProvider()).isEqualTo("KAKAOPAY");
        assertThat(response.payment().paymentId()).isEqualTo(response.orderNumber());
        verify(idempotencyService).complete(any(), eq(201), eq("PURCHASE"), isNull(), eq(response));
    }

    @Test
    void 같은_멱등_요청은_저장된_최초_응답을_재사용한다() {
        CreatePurchaseResponse replay = mock(CreatePurchaseResponse.class);
        when(idempotencyService.claim(eq(7L), eq("CREATE_PURCHASE"), anyString(), eq("shopItemId=101"),
                eq(CreatePurchaseResponse.class)))
                .thenReturn(new ApiIdempotencyService.Claim<>(mock(ApiIdempotencyRecord.class), true, replay));

        CreatePurchaseResponse response = service.create(7L, 101L, UUID.randomUUID().toString());

        assertThat(response).isSameAs(replay);
        verifyNoInteractions(shopItemRepository, purchaseRepository, userRepository);
    }
}
