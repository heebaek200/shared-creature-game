// @ts-check
import { apiRequest } from '../../api/client.js';
import { mockApi } from '../../mocks/mock-api.js';

const USE_MOCK = import.meta.env.VITE_USE_MOCK_API === 'true';

/**
 * 현재 그룹의 크리처 홈 Snapshot 정보를 조회합니다.
 * GET /api/v1/groups/me/creature-home
 */
export async function getCreatureHome() {
    if (USE_MOCK) {
        return await mockApi.getCreatureHome();
    }

    return apiRequest('/api/v1/groups/me/creature-home');
}

/**
 * 현재 선택 가능한 크리처 종 목록을 조회합니다.
 * GET /api/v1/creature-species
 */
export async function getActiveCreatureSpecies() {
    if (USE_MOCK) {
        return await mockApi.getActiveCreatureSpecies();
    }

    return apiRequest('/api/v1/creature-species');
}
