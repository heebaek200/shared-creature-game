package com.team.sharedcreature.avatar.domain;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * shop_items 테이블의 상태와 이력을 보존하는 JPA 엔티티입니다.
 */
@Getter
@Entity
@Table(name = "shop_items", indexes =
        @Index(name = "ix_shop_items_status_slot", columnList = "status, slot_code"))
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ShopItem {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "code", length = 50, unique = true, nullable = false)
    private String code;

    @Column(name = "name", length = 100, nullable = false)
    private String name;

    @Column(name = "description", length = 500, nullable = false)
    private String description;

    @Column(name = "price_krw", nullable = false)
    private Long priceKrw;

    @Column(name = "slot_code", length = 30, nullable = false)
    private String slotCode;

    @Column(name = "asset_key", length = 255, nullable = false)
    private String assetKey;

    @Column(name = "status", length = 30, nullable = false)
    private String status;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;
}
