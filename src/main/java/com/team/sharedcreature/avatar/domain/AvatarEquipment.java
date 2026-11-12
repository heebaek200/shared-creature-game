package com.team.sharedcreature.avatar.domain;

import com.team.sharedcreature.auth.domain.User;
import com.team.sharedcreature._core.exception.BusinessException;
import com.team.sharedcreature._core.exception.ErrorCode;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.Set;

/**
 * avatar_equipments 테이블의 상태와 이력을 보존하는 JPA 엔티티입니다.
 */
@Getter
@Entity
@Table(name = "avatar_equipments", uniqueConstraints = {
        @UniqueConstraint(name = "uq_avatar_equipments_user_slot", columnNames = {"user_id", "slot_code"}),
        @UniqueConstraint(name = "uq_avatar_equipments_owned_item", columnNames = "owned_item_id")
})
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class AvatarEquipment {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Column(name = "slot_code", length = 30, nullable = false)
    private String slotCode;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "owned_item_id")
    private OwnedItem ownedItem;

    @Version
    @Column(name = "version", nullable = false)
    private Long version;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    /**
     * 아이템이 장착되지 않은 슬롯을 생성합니다.
     *
     * @param user 슬롯을 소유한 사용자
     * @param slotCode 장착 슬롯 코드
     * @param now 서버에서 결정한 현재 시각
     */
    private AvatarEquipment(User user, String slotCode, Instant now) {
        this.user = user;
        this.slotCode = slotCode;
        this.createdAt = now;
        this.updatedAt = now;
    }

    /**
     * 허용된 슬롯 코드로 빈 아바타 슬롯을 생성합니다.
     *
     * @param user 슬롯을 소유한 사용자
     * @param slotCode HEAD, FACE, BODY, ACCESSORY 중 하나
     * @param now 서버에서 결정한 현재 시각
     * @return 아이템이 장착되지 않은 슬롯
     * @throws BusinessException 허용되지 않은 슬롯 코드인 경우
     */
    public static AvatarEquipment createEmpty(
            User user,
            String slotCode,
            Instant now
    ) {
        if (slotCode == null
                || !Set.of("HEAD", "FACE", "BODY", "ACCESSORY").contains(slotCode)) {
            throw new BusinessException(ErrorCode.INVALID_FIELD_VALUE); // 허용하지 않는 슬롯으로 생성 시도
        }

        return new AvatarEquipment(user, slotCode, now);
    }

    /**
     * 현재 슬롯에 아이템을 장착하거나 기존 아이템을 교체합니다.
     *
     * 호출자는 환불 상태 검사와 최초 사용 확정을 마친 뒤 호출해야 합니다.
     *
     * @param item 장착할 보유 아이템
     * @param now 서버에서 결정한 현재 시각
     * @throws BusinessException 소유자가 다르거나 회수된 아이템인 경우,
     *                          슬롯이 다르거나 사용 확정이 끝나지 않은 경우
     */
    public void equip(OwnedItem item, Instant now) {
        if (!user.getId().equals(item.getUser().getId())) {
            throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND); // 아이템 소유자가 슬롯 소유자와 다름
        }

        if (item.getRevokedAt() != null
                || "REVOKED".equals(item.getStatus())) {
            throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND); // 회수된 아이템이거나 회수 시각이 있음
        }

        if (!slotCode.equals(item.getShopItem().getSlotCode())) {
            throw new BusinessException(ErrorCode.SLOT_MISMATCH); // 아이템의 슬롯과 장착 대상 슬롯이 다름
        }

        // 사용 확정을 거치지 않은 아이템이 슬롯에 연결되는 것을 방지합니다.
        if (!"OWNED_USED".equals(item.getStatus()) || item.getUsedAt() == null) {
            throw new BusinessException(ErrorCode.CONCURRENT_MODIFICATION); // 사용 확정이 끝나지 않은 아이템을 장착
        }

        // 이미 같은 아이템을 장착했다면 불필요한 상태 변경을 하지 않습니다.
        if (ownedItem != null && ownedItem.getId().equals(item.getId())) {
            return;
        }

        this.ownedItem = item;
        this.updatedAt = now;
    }

    /**
     * 현재 슬롯의 아이템을 해제합니다.
     *
     * @param now 서버에서 결정한 현재 시각
     */
    public void unequip(Instant now) {
        if (ownedItem == null) {
            return;
        }

        // 슬롯 연결만 제거하고 보유 아이템의 상태와 최초 사용 시각은 유지합니다.
        this.ownedItem = null;
        this.updatedAt = now;
    }
}
