package com.team.sharedcreature.avatar.domain;

import com.team.sharedcreature.auth.domain.User;
import com.team.sharedcreature.payment.domain.Purchase;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * owned_items 테이블의 상태와 이력을 보존하는 JPA 엔티티입니다.
 */
@Getter
@Entity
@Table(name = "owned_items", uniqueConstraints =
        @UniqueConstraint(name = "uq_owned_items_purchase", columnNames = "purchase_id"), indexes = {
        @Index(name = "ix_owned_items_user_status", columnList = "user_id, status"),
        @Index(name = "ix_owned_items_shop_status", columnList = "shop_item_id, status")
})
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class OwnedItem {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "purchase_id", nullable = false)
    private Purchase purchase;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "shop_item_id", nullable = false)
    private ShopItem shopItem;

    @Column(name = "status", length = 30, nullable = false)
    private String status;

    @Column(name = "used_at")
    private Instant usedAt;

    @Column(name = "revoked_at")
    private Instant revokedAt;

    @Version
    @Column(name = "version", nullable = false)
    private Long version;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    private OwnedItem(Purchase purchase, User user, ShopItem shopItem, Instant now) {
        this.purchase = purchase;
        this.user = user;
        this.shopItem = shopItem;
        this.status = "OWNED_UNUSED";
        this.createdAt = now;
        this.updatedAt = now;
    }

    /** 결제 완료 주문으로 지급되는 미사용 보유 아이템을 생성합니다. */
    public static OwnedItem grant(Purchase purchase, Instant now) {
        return new OwnedItem(purchase, purchase.getUser(), purchase.getShopItem(), now);
    }
}
