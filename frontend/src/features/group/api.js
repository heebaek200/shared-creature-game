// @ts-check
import { apiRequest } from '../../api/client.js';
import { mockApi } from '../../mocks/mock-api.js';

const useMock = import.meta.env.VITE_USE_MOCK_API === 'true';

/** 새 공개 그룹을 만들고 생성 결과를 반환합니다. */
export function createGroup(input) {
  return useMock
    ? mockApi.createGroup(input)
    : apiRequest('/api/v1/groups', { method: 'POST', body: input });
}
