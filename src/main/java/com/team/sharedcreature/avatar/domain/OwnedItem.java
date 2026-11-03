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
@Table(name = "owned_items")
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

    @Column(name = "used_at", nullable = false)
    private Instant usedAt;

    @Column(name = "revoked_at", nullable = false)
    private Instant revokedAt;

    @Version
    @Column(name = "version", nullable = false)
    private Long version;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;
}
