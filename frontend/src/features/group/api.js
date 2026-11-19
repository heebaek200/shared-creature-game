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
 * 현재 로그인한 사용자의 그룹 정보를 조회합니다.
 * GET /api/v1/groups/me
 */
export function getMyGroup() {
    return useMock
        ? mockApi.getMyGroup()
        : apiRequest('/api/v1/groups/me');
}

/**
 * 현재 소속 그룹의 활성 구성원 목록을 조회합니다.
 * 서버에서 적용한 페이지 순서와 메타데이터를 그대로 전달합니다.
 * GET /api/v1/groups/me/members
 *
 * @param {{page?: number, size?: number}} options 페이지 조회 조건
 * @returns {Promise<{data: object[], meta: object}>} 목록과 페이지 정보
 */
export function listMyGroupMembers({page = 0, size = 20} = {}) {
    // Query Parameter는 공통 URLSearchParams로 생성합니다.
    const query = new URLSearchParams({
        page: String(page),
        size: String(size),
    });

    // 새로운 Mock 분기 없이 실제 백엔드 API를 호출합니다.
    return apiRequestWithMeta(
        `/api/v1/groups/me/members?${query.toString()}`
    );
}

/**
 * 현재 사용자가 소속된 그룹에서 자진 탈퇴합니다.
 * 서버에서 관리자 권한과 그룹 인원수를 재검증합니다.
 * 성공하면 가입 제한 종료 시각과 그룹 비활성화 여부를 반환합니다.
 *
 * @returns {Promise<object>} 탈퇴 처리 결과
 */
export function leaveMyGroup() {
    return apiRequest('/api/v1/groups/me/membership', {
        method: 'DELETE'
    });
}

/**
 * 같은 그룹에 소속된 활성 회원을 신고합니다.
 * 신고 내용은 서버에서 검증하고 앞뒤 공백을 제거해 저장합니다.
 * 신고 접수는 강퇴와 별개의 관리 업무입니다.
 *
 * @param {{reportedUserId: number, category: string, content: string, chatMessageId?: string}} input 신고 정보
 * @returns {Promise<object>} 신고 접수 결과
 */
export function createUserReport(input) {
    return apiRequest('/api/v1/reports', {
        method: 'POST',
        body: {
            reportedUserId: input.reportedUserId,
            category: input.category,
            content: input.content,
            ...(input.chatMessageId ? {chatMessageId: input.chatMessageId} : {})
        }
    });
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




