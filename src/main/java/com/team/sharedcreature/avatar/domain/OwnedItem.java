package com.team.sharedcreature.avatar.domain;

import com.team.sharedcreature.auth.domain.User;
import com.team.sharedcreature.payment.domain.Purchase;
import com.team.sharedcreature._core.exception.BusinessException;
import com.team.sharedcreature._core.exception.ErrorCode;
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

    /**
     * 최초 장착 동의를 확인하고 아이템 사용을 확정합니다.
     *
     * 이미 사용한 아이템의 최초 사용 시각은 변경하지 않습니다.
     * 호출자는 소유권·슬롯·환불 상태를 검사하고 필요한 잠금을 확보해야 합니다.
     *
     * @param confirmFirstUse 최초 사용에 동의했는지 여부
     * @param now 서버에서 결정한 현재 시각
     * @throws BusinessException 회수되었거나 사용할 수 없는 상태인 경우,
     *                          사용 상태와 이력이 불일치하거나 최초 사용 동의가 없는 경우
     */
    public void confirmUse(boolean confirmFirstUse, Instant now) {
        // 회수 시각이 있거나 유효한 보유 상태가 아니면 사용을 거부합니다.
        if (revokedAt != null
                || (!"OWNED_UNUSED".equals(status)
                && !"OWNED_USED".equals(status))) {
            throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND);
        }

        if ("OWNED_USED".equals(status)) {
            // 사용 상태인데 사용 시각이 없다면 임의로 이력을 복구하지 않습니다.
            if (usedAt == null) {
                throw new BusinessException(ErrorCode.CONCURRENT_MODIFICATION);
            }

            // 재장착에서는 최초 사용 시각과 변경 시각을 그대로 유지합니다.
            return;
        }

        if (usedAt != null) {
            throw new BusinessException(ErrorCode.CONCURRENT_MODIFICATION);
        }

        if (!confirmFirstUse) {
            throw new BusinessException(
                    ErrorCode.FIRST_USE_CONFIRMATION_REQUIRED
            );
        }

        this.status = "OWNED_USED";
        this.usedAt = now;
        this.updatedAt = now;
    }
}
