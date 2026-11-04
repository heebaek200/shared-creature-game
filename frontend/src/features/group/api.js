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

/**
 * 공개 초대 정보를 조회합니다.
 */
export function getPublicInvitation(token) {
  return useMock
      ? mockApi.getPublicInvitation(token)
      : apiRequest((`/api/v1/invitations/${encodeURIComponent(token)}`));
}

/**
 * 초대를 수락합니다.
 */
export function acceptInvitation(token) {
  return useMock
      ? mockApi.acceptInvitation(token)
      : apiRequest((`/api/v1/invitations/${encodeURIComponent(token)}/accept`,
          {method: 'POST'}));
}

/**
 * 초대를 거절합니다.
 */
export function rejectInvitation(token) {
  return useMock
      ? mockApi.rejectInvitation(token)
      : apiRequest((`/api/v1/invitations/${encodeURIComponent(token)}/reject`,
          {method: 'POST'}));
}




