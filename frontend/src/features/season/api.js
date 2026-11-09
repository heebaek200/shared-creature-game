// @ts-check
import { apiRequest } from '../../api/client.js';
import { mockApi } from '../../mocks/mock-api.js';

const USE_MOCK = import.meta.env.VITE_USE_MOCK_API === 'true';

/**
 * 현재 진행 중인 시즌 정보를 조회합니다.
 * GET /api/v1/seasons/current
 */
export async function getCurrentSeason() {
    if (USE_MOCK) {
        return await mockApi.getCurrentSeason();
    }

    return apiRequest('/api/v1/seasons/current');
}
