
import {apiRequest} from '../../api/client.js';

/**
 * 구성원의 부 관리자 역할을 부여하거나 회수합니다.
 * PRIMARY 관리자만 실행할 수 있는 API입니다.
 * 실제 권한과 대상의 소속 상태는 서버가 검증합니다.
 *
 * @param {number} userId 대상 사용자 ID
 * @param {'MEMBER'|'DEPUTY'} role 변경할 역할
 * @returns {Promise<object>} 변경된 구성원 정보
 */
export function changeMemberRole(userId, role) {
    return apiRequest(
        `/api/v1/groups/me/members/${userId}/role`,
        {
            method: 'PATCH',
            body: {role}
        }
    );
}

/**
 * 비활동 구성원을 그룹에서 강퇴합니다.
 * 요청 시 강퇴 사유를 전달하며 대상에게 24시간 가입 제한이 적용됩니다.
 * 관리자 권한과 72시간 10분 기준은 서버가 최종 검증합니다.
 *
 * @param {number} userId 대상 사용자 ID
 * @param {string} reason 강퇴 사유
 * @returns {Promise<object>} 강퇴 처리 결과
 */
export function removeGroupMember(userId, reason) {
    return apiRequest(
        `/api/v1/groups/me/members/${userId}/removals`,
        {
            method: 'POST',
            body: {reason}
        }
    );
}

/**
 * 그룹의 주 관리자 권한을 다른 활성 회원에게 이전합니다.
 * 호출자는 일반 회원으로 변경되고 관리 권한을 잃습니다.
 * 성공하면 변경된 그룹 상세 정보가 반환됩니다.
 *
 * @param {number} targetUserId 새 주 관리자 사용자 ID
 * @returns {Promise<object>} 변경된 그룹 정보
 */
export function transferPrimaryAdmin(targetUserId) {
    return apiRequest('/api/v1/groups/me/primary-transfer', {
        method: 'POST',
        body: {targetUserId}
    });
}

/**
 * 현재 그룹의 초대 링크 발급 정보를 조회합니다.
 * PRIMARY만 요청할 수 있으며 기존 링크의 원문은 반환하지 않습니다.
 * 발급 시각·발급자·유효 기간 등의 메타데이터만 전달합니다.
 *
 * @returns {Promise<object>} 초대 링크 발급 정보
 */
export function getInvitationManagement() {
    return apiRequest('/api/v1/groups/me/invitation');
}

/**
 * 기존 초대 링크를 무효화하고 새 링크를 발급합니다.
 * PRIMARY 권한은 서버에서 최신 멤버십으로 다시 검사합니다.
 * 새 초대 URL은 성공 응답에만 포함되므로 즉시 표시해야 합니다.
 *
 * @returns {Promise<object>} 새 초대 URL과 발급 정보
 */
export function rotateInvitation() {
    return apiRequest('/api/v1/groups/me/invitation/rotate', {
        method: 'POST'
    });
}
