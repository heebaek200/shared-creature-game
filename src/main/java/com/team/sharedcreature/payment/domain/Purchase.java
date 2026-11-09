package com.team.sharedcreature.payment.domain;

import com.team.sharedcreature.auth.domain.User;
import com.team.sharedcreature.avatar.domain.ShopItem;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * purchases 테이블의 상태와 이력을 보존하는 JPA 엔티티입니다.
 */
@Getter
@Entity
@Table(name = "purchases", indexes = {
        @Index(name = "ix_purchases_user_created", columnList = "user_id, created_at"),
        @Index(name = "ix_purchases_status_created", columnList = "status, created_at")
})
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Purchase {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "order_number", length = 36, unique = true, nullable = false)
    private String orderNumber;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "shop_item_id", nullable = false)
    private ShopItem shopItem;

    @Column(name = "item_code_snapshot", length = 50, nullable = false)
    private String itemCodeSnapshot;

    @Column(name = "item_name_snapshot", length = 100, nullable = false)
    private String itemNameSnapshot;

    @Column(name = "slot_code_snapshot", length = 30, nullable = false)
    private String slotCodeSnapshot;

    @Column(name = "price_krw_snapshot", nullable = false)
    private Long priceKrwSnapshot;

    @Column(name = "currency", length = 3, nullable = false)
    private String currency;

    @Column(name = "status", length = 30, nullable = false)
    private String status;

    @Version
    @Column(name = "version", nullable = false)
    private Long version;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    private Purchase(
            String orderNumber,
            User user,
            ShopItem shopItem,
            String itemCodeSnapshot,
            String itemNameSnapshot,
            String slotCodeSnapshot,
            Long priceKrwSnapshot,
            Instant now
    ) {
        this.orderNumber = orderNumber;
        this.user = user;
        this.shopItem = shopItem;
        this.itemCodeSnapshot = itemCodeSnapshot;
        this.itemNameSnapshot = itemNameSnapshot;
        this.slotCodeSnapshot = slotCodeSnapshot;
        this.priceKrwSnapshot = priceKrwSnapshot;
        this.currency = "KRW";
        this.status = PurchaseStatus.READY.name();
        this.createdAt = now;
        this.updatedAt = now;
    }

    /** 서버에서 확정한 상품 Snapshot으로 결제 대기 주문을 생성합니다. */
    public static Purchase ready(String orderNumber, User user, ShopItem shopItem, Instant now) {
        return new Purchase(
                orderNumber,
                user,
                shopItem,
                shopItem.getCode(),
                shopItem.getName(),
                shopItem.getSlotCode(),
                shopItem.getPriceKrw(),
                now
        );
    }

    /** 결제 검증이 끝난 주문을 한 번만 결제 완료 상태로 전환합니다. */
    public void markPaid(Instant now) {
        if (PurchaseStatus.PAID.name().equals(status)) {
            return;
        }
        if (!PurchaseStatus.READY.name().equals(status)) {
            throw new IllegalStateException("READY 구매만 PAID로 전환할 수 있습니다.");
        }
        status = PurchaseStatus.PAID.name();
        updatedAt = now;
    }

    /** 공급자가 명확히 결제 실패를 확정한 주문을 실패 상태로 전환합니다. */
    public void markFailed(Instant now) {
        if (!PurchaseStatus.READY.name().equals(status)) {
            return;
        }
        status = PurchaseStatus.FAILED.name();
        updatedAt = now;
    }
}
