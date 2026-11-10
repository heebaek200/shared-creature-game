// @ts-check
import {apiRequest, apiRequestWithMeta} from '../../api/client.js';
import {mockApi} from '../../mocks/mock-api.js';

const useMock = import.meta.env.VITE_USE_MOCK_API === 'true';

/** 새 공개 그룹을 만들고 생성 결과를 반환합니다. */
export function createGroup(input) {
    return useMock
        ? mockApi.createGroup(input)
        : apiRequest('/api/v1/groups', {method: 'POST', body: input});
}


/**
 * 공개 그룹 목록을 검색하고 페이지 정보를 함께 반환합니다.
 */
export function listPublicGroups({q = '', page = 0, size = 20} = {}) {
    if (useMock) {
        return mockApi.listPublicGroups({q, page, size});
    }
    const query = new URLSearchParams({
        q,
        page: String(page),
        size: String(size)
    });
    return apiRequestWithMeta(`/api/v1/groups?${query.toString()}`);
}

/**
 * 공개 그룹에 가입합니다
 */
export function joinGroup(groupId) {
    return useMock
        ? mockApi.joinGroup(groupId)
        : apiRequest(`/api/v1/groups/${groupId}/memberships`, {method: "POST"});
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
        : apiRequest(`/api/v1/invitations/${encodeURIComponent(token)}/accept`,
            { method: 'POST' });
}


/**
 * 초대를 거절합니다.
 */
export function rejectInvitation(token) {
    return useMock
        ? mockApi.rejectInvitation(token)
        : apiRequest(
            `/api/v1/invitations/${encodeURIComponent(token)}/reject`,
            { method: 'POST' });
}




