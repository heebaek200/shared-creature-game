// @ts-check
import { apiRequest } from '../../api/client.js';
import { mockApi } from '../../mocks/mock-api.js';

const useMock = import.meta.env.VITE_USE_MOCK_API === 'true';

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
