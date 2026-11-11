package com.team.sharedcreature.avatar.infrastructure;

import com.team.sharedcreature.avatar.domain.AvatarEquipment;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

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
}