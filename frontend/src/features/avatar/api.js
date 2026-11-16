// @ts-check
import {
    apiRequest,
    apiRequestWithMeta,
} from '../../api/client.js';
import { mockApi } from '../../mocks/mock-api.js';

const useMock = import.meta.env.VITE_USE_MOCK_API === 'true';

/**
 * 내 아바타의 전체 장착 상태를 조회합니다.
 */
export function getAvatar() {
    return useMock
        ? mockApi.getAvatar()
        : apiRequest('/api/v1/avatar');
}

/**
 * 내 보유 아이템 목록을 페이지 정보와 함께 조회합니다.
 *
 * @param {{
 *   page?: number,
 *   size?: number,
 *   slot?: 'HEAD' | 'FACE' | 'BODY' | 'ACCESSORY',
 *   status?: 'OWNED_UNUSED' | 'OWNED_USED' | 'REVOKED'
 * }} filters
 */
export function listOwnedItems(filters = {}) {
    const {
        page = 0,
        size = 20,
        slot,
        status,
    } = filters;

    // 선택하지 않은 필터는 요청에서 생략합니다.
    const params = {
        page,
        size,
        ...(slot ? { slot } : {}),
        ...(status ? { status } : {}),
    };

    if (useMock) {
        return mockApi.listOwnedItems(params);
    }

    const query = new URLSearchParams();

    for (const [key, value] of Object.entries(params)) {
        query.set(key, String(value));
    }

    return apiRequestWithMeta(
        `/api/v1/owned-items?${query.toString()}`
    );
}

/**
 * 아이템을 장착하고 전체 장착 상태를 반환합니다.
 * 최초 사용 동의를 받은 경우에만 confirmFirstUse: true를 전달합니다.
 *
 * @param {'HEAD' | 'FACE' | 'BODY' | 'ACCESSORY'} slot
 * @param {{
 *   ownedItemId: number,
 *   confirmFirstUse?: boolean
 * }} input
 */
export function equipAvatarItem(slot, input) {
    const {
        ownedItemId,
        confirmFirstUse = false,
    } = input;

    const body = {
        ownedItemId,
        confirmFirstUse,
    };

    return useMock
        ? mockApi.equipAvatarItem(slot, body)
        : apiRequest(
            `/api/v1/avatar/equipments/${encodeURIComponent(slot)}`,
            {
                method: 'PUT',
                body,
            }
        );
}

/**
 * 지정한 슬롯의 장착을 해제하고 전체 장착 상태를 반환합니다.
 *
 * @param {'HEAD' | 'FACE' | 'BODY' | 'ACCESSORY'} slot
 */
export function unequipAvatarItem(slot) {
    return useMock
        ? mockApi.unequipAvatarItem(slot)
        : apiRequest(
            `/api/v1/avatar/equipments/${encodeURIComponent(slot)}`,
            {
                method: 'DELETE',
            }
        );
}