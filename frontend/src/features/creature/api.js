// @ts-check
import { apiRequest } from '../../api/client.js';
import { mockApi } from '../../mocks/mock-api.js';

const useMock = import.meta.env.VITE_USE_MOCK_API === 'true';
if (import.meta.env.PROD && useMock) {
    throw Error('Mock API')
}

/*
 * 공동 크리처 홈 화면 Snapshot 데이터를 조회합니다.
 */
export function getCreatureHome() {
    return useMock
        ? Promise.resolve(mockApi.creatureHome)
        : apiRequest('/api/v1/groups/me/creature-home');
}