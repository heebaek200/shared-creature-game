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
}
