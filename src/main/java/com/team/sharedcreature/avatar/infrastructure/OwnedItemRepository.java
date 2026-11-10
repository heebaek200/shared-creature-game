package com.team.sharedcreature.avatar.infrastructure;

import com.team.sharedcreature.avatar.domain.OwnedItem;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

/**
 * OwnedItem 엔티티의 기본 영속성 연산을 제공합니다.
 */
public interface OwnedItemRepository extends JpaRepository<OwnedItem, Long> {
    Optional<OwnedItem> findByPurchaseId(Long purchaseId);
}
