package com.team.sharedcreature.payment.application;

import com.team.sharedcreature._core.exception.BusinessException;
import com.team.sharedcreature._core.exception.ErrorCode;
import com.team.sharedcreature.admin.application.ApiIdempotencyService;
import com.team.sharedcreature.auth.infrastructure.UserRepository;
import com.team.sharedcreature.avatar.domain.ShopItem;
import com.team.sharedcreature.avatar.infrastructure.ShopItemRepository;
import com.team.sharedcreature.payment.api.CreatePurchaseResponse;
import com.team.sharedcreature.payment.domain.Purchase;
import com.team.sharedcreature.payment.infrastructure.PortOneProperties;
import com.team.sharedcreature.payment.infrastructure.PurchaseRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.UUID;

/** 서버 상품 가격으로 결제 대기 주문을 멱등 생성합니다. */
@Service
public class PurchaseService {

    private static final String OPERATION = "CREATE_PURCHASE";

    private final PurchaseRepository purchaseRepository;
    private final ShopItemRepository shopItemRepository;
    private final UserRepository userRepository;
    private final ApiIdempotencyService idempotencyService;
    private final PortOneProperties portOneProperties;
    private final Clock clock;

    /** 구매 생성에 필요한 원장과 공개 PortOne 설정을 주입받습니다. */
    public PurchaseService(
            PurchaseRepository purchaseRepository,
            ShopItemRepository shopItemRepository,
            UserRepository userRepository,
            ApiIdempotencyService idempotencyService,
            PortOneProperties portOneProperties,
            Clock clock
    ) {
        this.purchaseRepository = purchaseRepository;
        this.shopItemRepository = shopItemRepository;
        this.userRepository = userRepository;
        this.idempotencyService = idempotencyService;
        this.portOneProperties = portOneProperties;
        this.clock = clock;
    }

    /** 같은 사용자·키·상품 요청에는 최초 주문 응답을 재사용합니다. */
    @Transactional
    public CreatePurchaseResponse create(Long userId, Long shopItemId, String idempotencyKey) {
        var claim = idempotencyService.claim(
                userId,
                OPERATION,
                idempotencyKey,
                "shopItemId=" + shopItemId,
                CreatePurchaseResponse.class
        );
        if (claim.replayed()) {
            return claim.response();
        }

        ShopItem shopItem = shopItemRepository.findByIdAndStatus(shopItemId, "ACTIVE")
                .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND));
        Purchase purchase = purchaseRepository.save(Purchase.ready(
                UUID.randomUUID().toString(),
                userRepository.getReferenceById(userId),
                shopItem,
                clock.instant()
        ));
        CreatePurchaseResponse response = response(purchase);
        idempotencyService.complete(claim, 201, "PURCHASE", purchase.getId(), response);
        return response;
    }

    private CreatePurchaseResponse response(Purchase purchase) {
        var payment = new CreatePurchaseResponse.Payment(
                portOneProperties.storeId(),
                portOneProperties.channelKey(),
                purchase.getOrderNumber(),
                purchase.getItemNameSnapshot(),
                purchase.getPriceKrwSnapshot(),
                purchase.getCurrency(),
                "EASY_PAY",
                new CreatePurchaseResponse.EasyPay("KAKAOPAY"),
                new CreatePurchaseResponse.WindowType("IFRAME", "REDIRECTION")
        );
        return new CreatePurchaseResponse(
                purchase.getId(),
                purchase.getOrderNumber(),
                purchase.getShopItem().getId(),
                purchase.getItemNameSnapshot(),
                purchase.getPriceKrwSnapshot(),
                purchase.getCurrency(),
                payment,
                purchase.getCreatedAt()
        );
    }
}
