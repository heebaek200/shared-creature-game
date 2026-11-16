package com.team.sharedcreature.avatar.infrastructure;

import com.team.sharedcreature.avatar.domain.AvatarEquipment;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import jakarta.persistence.LockModeType;


import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * 아바타 슬롯별 장착 정보의 저장과 조회를 담당합니다.
 * 기본 영속성 연산을 제공
 */
public interface AvatarEquipmentRepository
        extends JpaRepository<AvatarEquipment, Long> {

    /**
     * 특정 사용자의 아바타 슬롯별 장착 정보를 조회합니다.
     *
     * @param userId 로그인한 사용자 ID
     * @return 해당 사용자의 슬롯별 장착 정보, 없으면 빈 목록
     */
    // 응답에 필요한 보유 아이템과 상품을 함께 조회해 추가 조회를 방지합니다.
    @EntityGraph(attributePaths = {
            "ownedItem",
            "ownedItem.shopItem"
    })
    List<AvatarEquipment> findAllByUserId(Long userId); // 장착하지 않은 슬롯도 응답에는 null로 포함

    /** 접속 구성원들의 장착 슬롯과 상품 외형을 한 번에 조회합니다. */
    @EntityGraph(attributePaths = {
            "user",
            "ownedItem",
            "ownedItem.shopItem"
    })
    List<AvatarEquipment> findAllByUserIdIn(Collection<Long> userIds);

    /**
     * 사용자의 특정 아바타 슬롯을 비관적 쓰기 잠금으로 조회합니다.
     *
     * @param userId 로그인한 사용자 ID
     * @param slotCode 조회할 장착 슬롯 코드
     * @return 잠근 슬롯 정보, 슬롯 행이 없으면 빈 Optional
     */
    // 슬롯 행 자체를 잠그며 연결된 아이템의 잠금은 별도로 처리합니다.
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
        select equipment
        from AvatarEquipment equipment
        where equipment.user.id = :userId
        and equipment.slotCode = :slotCode
        """)
    Optional<AvatarEquipment> findByUserIdAndSlotCodeForUpdate(
            @Param("userId") Long userId,
            @Param("slotCode") String slotCode
    );
}
