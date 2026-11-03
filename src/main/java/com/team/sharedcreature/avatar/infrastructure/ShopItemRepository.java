package com.team.sharedcreature.avatar.infrastructure;

import com.team.sharedcreature.avatar.domain.ShopItem;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * ShopItem 엔티티의 기본 영속성 연산을 제공합니다.
 */
public interface ShopItemRepository extends JpaRepository<ShopItem, Long> {
}
