package com.team.sharedcreature.avatar.application;

import com.team.sharedcreature._core.exception.BusinessException;
import com.team.sharedcreature._core.exception.ErrorCode;
import com.team.sharedcreature.avatar.api.ShopItemResponse;
import com.team.sharedcreature.avatar.domain.ShopItem;
import com.team.sharedcreature.avatar.infrastructure.ShopItemRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 판매 중인 상점 상품의 목록과 상세 정보를 조회합니다.
 *
 * 조회 결과는 엔티티 대신 응답 DTO로 반환합니다.
 */
@Service
@Transactional(readOnly = true)
public class ShopQueryService {

    private final ShopItemRepository shopItemRepository;

    /**
     * 상품 조회에 필요한 Repository를 주입받습니다.
     *
     * @param shopItemRepository 상점 상품 Repository
     */
    public ShopQueryService(ShopItemRepository shopItemRepository) {
        this.shopItemRepository = shopItemRepository;
    }

    /**
     * 판매 중인 상품을 슬롯 조건에 따라 페이지 단위로 조회합니다.
     *
     * @param page 검증된 페이지 번호, 0 이상
     * @param size 검증된 페이지 크기, 1~50
     * @param slot 검증된 슬롯 코드, null이면 전체 슬롯
     * @return 상품 정보와 페이지 정보를 포함한 조회 결과
     */
    public Page<ShopItemResponse> list(int page, int size, String slot) {
        // 상품 ID 순으로 정렬해 페이지 조회 순서를 일정하게 유지합니다.
        PageRequest pageable = PageRequest.of(
                page,
                size,
                Sort.by(Sort.Direction.ASC, "id")
        );

        // 클라이언트가 상태를 선택하지 못하도록 ACTIVE를 고정합니다.
        Page<ShopItem> items = slot == null // 슬롯 미지정이면 전체 슬롯 조회
                ? shopItemRepository.findAllByStatus("ACTIVE", pageable)
                : shopItemRepository.findAllByStatusAndSlotCode(
                "ACTIVE",
                slot,
                pageable
        );

        return items.map(ShopItemResponse::from); // 페이지 정보를 유지하면서 상품을 DTO로 변환
    }

    /**
     * 판매 중인 상품의 상세 정보를 조회합니다.
     *
     * @param itemId 검증된 상품 ID, 1 이상
     * @return 상품 상세 정보
     * @throws BusinessException 상품이 없거나 판매 중인 상태가 아닌 경우
     */
    public ShopItemResponse get(Long itemId) {
        // 판매 중이 아닌 상품도 존재하지 않는 상품과 동일하게 처리합니다.
        ShopItem shopItem = shopItemRepository
                .findByIdAndStatus(itemId, "ACTIVE") // 판매 중인 상품만 조회
                .orElseThrow(() ->
                        new BusinessException(ErrorCode.RESOURCE_NOT_FOUND) // 404 오류 처리
                );

        return ShopItemResponse.from(shopItem);
    }
}