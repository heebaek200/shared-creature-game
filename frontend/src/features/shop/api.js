// @ts-check
import { apiRequest, apiRequestWithMeta } from '../../api/client.js';
import { mockApi } from '../../mocks/mock-api.js';

const useMock = import.meta.env.VITE_USE_MOCK_API === 'true';

/**
 * 판매 중인 상품을 슬롯과 페이지 조건으로 조회합니다.
 * @param {{page?: number, size?: number, slot?: string}} filters
 */
export function listShopItems({ page = 0, size = 20, slot } = {}) {
    if (useMock) return mockApi.listShopItems({ page, size, slot });
    const query = new URLSearchParams({ page: String(page), size: String(size) });
    if (slot) query.set('slot', slot);
    return apiRequestWithMeta(`/api/v1/shop-items?${query}`);
}

/**
 * 판매 중인 상품 상세를 조회합니다.
 * 판매 중이 아니거나 존재하지 않는 상품은 서버가 RESOURCE_NOT_FOUND를 반환합니다.
 *
 * @param {number|string} shopItemId
 */
export function getShopItem(shopItemId) {
    return useMock
        ? mockApi.getShopItem(shopItemId)
        : apiRequest(
            `/api/v1/shop-items/${encodeURIComponent(String(shopItemId))}`
        );
}
