package com.team.sharedcreature.avatar.support;

import com.team.sharedcreature.auth.domain.User;
import com.team.sharedcreature.avatar.domain.OwnedItem;
import com.team.sharedcreature.avatar.domain.ShopItem;
import com.team.sharedcreature.payment.domain.Purchase;
import org.springframework.beans.BeanUtils;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;

/** 이슈 20 테스트에서 사용하는 실제 엔티티와 고정 시각을 준비합니다. */
public final class AvatarTestFixtures {

    public static final Instant CREATED_AT = Instant.parse("2026-11-11T00:00:00Z");
    public static final Instant NOW = CREATED_AT.plusSeconds(3600);

    private AvatarTestFixtures() {
    }

    /**
     * 영속 ID가 부여된 활성 사용자를 준비합니다.
     *
     * @param id 사용자 ID
     * @return 테스트 사용자
     */
    public static User user(Long id) {
        User user = User.register("USER", CREATED_AT);
        ReflectionTestUtils.setField(user, "id", id);
        return user;
    }

    /**
     * 결제가 완료된 구매로 지급한 미사용 아이템을 준비합니다.
     *
     * @param id 보유 아이템 ID
     * @param userId 소유자 ID
     * @param slot 상품 슬롯
     * @return 미사용 보유 아이템
     */
    public static OwnedItem unusedItem(Long id, Long userId, String slot) {
        User owner = user(userId);
        ShopItem shop = BeanUtils.instantiateClass(ShopItem.class);
        // 운영 생성 API를 추가하지 않고 테스트에서만 영속 식별자와 상품 값을 준비합니다.
        ReflectionTestUtils.setField(shop, "id", id + 100);
        ReflectionTestUtils.setField(shop, "code", "ITEM_" + id);
        ReflectionTestUtils.setField(shop, "name", "테스트 아이템 " + id);
        ReflectionTestUtils.setField(shop, "description", "아바타 테스트 상품");
        ReflectionTestUtils.setField(shop, "priceKrw", 1000L);
        ReflectionTestUtils.setField(shop, "slotCode", slot);
        ReflectionTestUtils.setField(shop, "assetKey", "avatar/test-" + id);
        ReflectionTestUtils.setField(shop, "status", "ACTIVE");
        Purchase purchase = Purchase.ready("ORDER_" + id, owner, shop, CREATED_AT);
        ReflectionTestUtils.setField(purchase, "id", id + 1000);
        purchase.markPaid(CREATED_AT);
        OwnedItem item = OwnedItem.grant(purchase, CREATED_AT);
        ReflectionTestUtils.setField(item, "id", id);
        return item;
    }
}
