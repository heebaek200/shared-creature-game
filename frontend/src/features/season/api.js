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

/**
 * 랭킹에서 선택할 수 있는 현재·종료 시즌을 최신 순으로 조회합니다.
 * #93 실제 테스트 데이터를 사용하므로 이 Operation에는 별도 목 응답을 두지 않습니다.
 */
export function listRankableSeasons() {
    return apiRequest('/api/v1/seasons');
}
