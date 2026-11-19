package com.team.sharedcreature.payment.domain;

import com.team.sharedcreature.auth.domain.User;
import com.team.sharedcreature.avatar.domain.ShopItem;
import com.team.sharedcreature.avatar.domain.AvatarSlot;
import com.team.sharedcreature.group.domain.Group;
import com.team.sharedcreature._core.exception.BusinessException;
import com.team.sharedcreature._core.exception.ErrorCode;
import java.util.Objects;
import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
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
    @JoinColumn(name = "group_id_snapshot")
    private Group groupSnapshot;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "shop_item_id", nullable = false)
    private ShopItem shopItem;

    @Column(name = "item_code_snapshot", length = 50, nullable = false)
    private String itemCodeSnapshot;

    @Column(name = "item_name_snapshot", length = 100, nullable = false)
    private String itemNameSnapshot;

    /** 상품의 현재 슬롯과 별도로 보존하는 구매 당시 장착 슬롯입니다. */
    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "slot_code_snapshot", length = 30, nullable = false)
    private AvatarSlot slotCodeSnapshot;

    @Column(name = "price_krw_snapshot", nullable = false)
    private Long priceKrwSnapshot;

    @Column(name = "currency", length = 3, nullable = false)
    private String currency;

    /** 구매 주문의 결제 진행 및 취소 상태입니다. */
    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "status", length = 30, nullable = false)
    private PurchaseStatus status;

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
            Group groupSnapshot,
            ShopItem shopItem,
            String itemCodeSnapshot,
            String itemNameSnapshot,
            AvatarSlot slotCodeSnapshot,
            Long priceKrwSnapshot,
            Instant now
    ) {
        this.orderNumber = orderNumber;
        this.user = user;
        this.groupSnapshot = groupSnapshot;
        this.shopItem = shopItem;
        this.itemCodeSnapshot = itemCodeSnapshot;
        this.itemNameSnapshot = itemNameSnapshot;
        this.slotCodeSnapshot = slotCodeSnapshot;
        this.priceKrwSnapshot = priceKrwSnapshot;
        this.currency = "KRW";
        this.status = PurchaseStatus.READY;
        this.createdAt = now;
        this.updatedAt = now;
    }

    /** 서버에서 확정한 상품 Snapshot으로 결제 대기 주문을 생성합니다. */
    public static Purchase ready(String orderNumber, User user, ShopItem shopItem, Instant now) {
        return ready(orderNumber, user, null, shopItem, now);
    }

    /** 구매 당시 활성 그룹을 Snapshot으로 보존한 결제 대기 주문을 생성합니다. */
    public static Purchase ready(
            String orderNumber,
            User user,
            Group groupSnapshot,
            ShopItem shopItem,
            Instant now
    ) {
        return new Purchase(
                orderNumber,
                user,
                groupSnapshot,
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
        if (status == PurchaseStatus.PAID) {
            return;
        }
        if (status != PurchaseStatus.READY) {
            throw new IllegalStateException("READY 구매만 PAID로 전환할 수 있습니다.");
        }
        status = PurchaseStatus.PAID;
        updatedAt = now;
    }

    /** 공급자가 명확히 결제 실패를 확정한 주문을 실패 상태로 전환합니다. */
    public void markFailed(Instant now) {
        if (status != PurchaseStatus.READY) {
            return;
        }
        status = PurchaseStatus.FAILED;
        updatedAt = now;
    }

    /**
     * PG 취소 성공이 확정된 구매를 결제 취소 상태로 전환합니다.
     * 환불 완료와 아이템 회수의 동일 트랜잭션 안에서 호출해야 합니다.
     *
     * @param now 구매 취소 결과를 반영하는 서버 시각
     * @return 처음 CANCELLED로 변경되었으면 true
     * @throws BusinessException 결제 완료 상태가 아닌 경우
     */
    public boolean markCancelledAfterRefund(Instant now) {
        Objects.requireNonNull(now, "now");

        // 같은 성공 결과를 반복 반영해도 변경 시각을 덮어쓰지 않습니다.
        if (status == PurchaseStatus.CANCELLED) {
            return false;
        }

        if (status != PurchaseStatus.PAID) {
            throw new BusinessException(
                    ErrorCode.CONCURRENT_MODIFICATION,
                    "결제 완료 구매만 환불 완료로 취소할 수 있습니다."
            );
        }

        this.status = PurchaseStatus.CANCELLED;
        this.updatedAt = now;

        return true;
    }
}
