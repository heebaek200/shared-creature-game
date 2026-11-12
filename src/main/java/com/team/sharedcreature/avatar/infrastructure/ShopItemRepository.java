package com.team.sharedcreature.avatar.infrastructure;

import com.team.sharedcreature.avatar.domain.ShopItem;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

/**
 * 상점 상품의 저장과 상태·슬롯별 조회를 담당합니다.
 * 기본 영속성 연산을 제공
 */
public interface ShopItemRepository extends JpaRepository<ShopItem, Long> {

    /**
     * 상품 ID와 상태가 일치하는 상품을 조회합니다.
     *
     * @param id 상품 ID
     * @param status 조회할 상품 상태
     * @return 조건에 맞는 상품, 없으면 빈 Optional
     */
    Optional<ShopItem> findByIdAndStatus(Long id, String status);

    /**
     * 지정한 상태의 상품 목록을 페이지 단위로 조회합니다.
     *
     * @param status 조회할 상품 상태
     * @param pageable 페이지 번호, 크기, 정렬 조건
     * @return 조건에 맞는 상품 페이지
     */
    Page<ShopItem> findAllByStatus(
            String status,
            Pageable pageable
    );

    /**
     * 지정한 상태와 슬롯에 해당하는 상품을 페이지 단위로 조회합니다.
     *
     * @param status 조회할 상품 상태
     * @param slotCode 장착 슬롯 코드
     * @param pageable 페이지 번호, 크기, 정렬 조건
     * @return 조건에 맞는 상품 페이지
     */
    Page<ShopItem> findAllByStatusAndSlotCode(
            String status,
            String slotCode,
            Pageable pageable
    );
}