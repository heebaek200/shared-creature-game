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

/**
 * 현재 그룹 시즌의 크리처 종을 선택하거나 변경합니다.
 * PRIMARY 또는 DEPUTY가 부화 마감 전에 실행할 수 있습니다.
 * 서버는 시즌 상태, 기한, 권한 및 종의 활성 상태를 다시 검증합니다.
 *
 * @param {number} creatureSpeciesId 선택할 크리처 종 ID
 * @returns {Promise<object>} 저장된 종 선택 결과
 */
export function selectCreatureSpecies(creatureSpeciesId) {
    return apiRequest(
        '/api/v1/groups/me/seasons/current/creature-selection',
        {
            method: 'PUT',
            body: {creatureSpeciesId}
        }
    );
}
