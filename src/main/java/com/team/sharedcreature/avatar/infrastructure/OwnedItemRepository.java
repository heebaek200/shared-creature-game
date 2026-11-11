package com.team.sharedcreature.avatar.infrastructure;

import com.team.sharedcreature.avatar.domain.OwnedItem;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

/**
 * 보유 아이템의 저장과 사용자별 조회를 담당합니다.
 * 기본 영속성 연산을 제공
 */
public interface OwnedItemRepository extends JpaRepository<OwnedItem, Long> {

    /**
     * 구매에 연결된 보유 아이템을 조회합니다.
     *
     * @param purchaseId 구매 ID
     * @return 해당 구매로 지급된 아이템, 없으면 빈 Optional
     */
    Optional<OwnedItem> findByPurchaseId(Long purchaseId);

    /**
     * 특정 사용자의 보유 아이템을 슬롯·상태 조건으로 조회합니다.
     *
     * @param userId 로그인한 사용자 ID
     * @param slot 장착 슬롯, null이면 전체 슬롯
     * @param status 보유 상태, null이면 전체 상태
     * @param pageable 페이지 번호·크기·정렬 조건
     * @return 조건에 맞는 보유 아이템 페이지
     */
    // 상품 정보를 함께 조회해 DTO 변환 중 상품별 추가 조회를 방지합니다.
    @EntityGraph(attributePaths = "shopItem")
    @Query(
            value = """
                    select item
                    from OwnedItem item
                    join item.shopItem shop
                    where item.user.id = :userId
                    and (:slot is null or shop.slotCode = :slot)
                    and (:status is null or item.status = :status)
                    """,
            // 전체 데이터 개수를 정확하고 효율적으로 카운트하기 위해 별도의 카운트 쿼리를 함께 정의
            countQuery = """
                    select count(item)
                    from OwnedItem item
                    join item.shopItem shop
                    where item.user.id = :userId
                    and (:slot is null or shop.slotCode = :slot)
                    and (:status is null or item.status = :status)
                    """
    )
    Page<OwnedItem> findAllForUser(
            @Param("userId") Long userId,
            @Param("slot") String slot,
            @Param("status") String status,
            Pageable pageable
    );
}