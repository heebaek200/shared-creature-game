// @ts-check
import {apiRequest, apiRequestWithMeta} from "../../api/client.js";

// 관리자 API 공통 경로
const ADMIN_API = '/api/v1/admin';

/**
 * Query Parameter 용 객체를 URL 문자열로 변환하는 유틸 함수.
 * 지정하지 않은 값은 제외하고 서버의 기본값을 사용한다.
 * page=0과 같은 유효한 값은 그대로 보존한다.
 */
function buildQuery(values) {
    const params = new URLSearchParams();

    // 값이 존재하는 항목만 Query Parameter에 추가한다.
    for (const [key, value] of Object.entries(values)) {
        if (value === null || value === undefined || value === '') {
            continue;
        }

        params.set(key, String(value));
    }

    const encoded = params.toString();

    return encoded ? `?${encoded}` : '';
}

/**
 * 전체 관리자 대시보드의 서버 집계 결과를 조회하는 API
 * 기간과 그룹을 필터로 전달, 그룹 미지정 시 전체를 조회한다.
 * 성공하면 요약 통계, 시계열, 분포, 관리 대기열 객체를 반환한다.
 *
 * @param {{range?: string, groupId?: number}} filters
 * @returns {Promise<any>} 대시보드 데이터
 */
export function getAdminDashboard({
          range = '7_DAYS',
          groupId
} = {}) {
    // 서버 명세의 기간, 그룹 필터만 전달한다
    const query = buildQuery({range, groupId});

    return apiRequest(`${ADMIN_API}/dashboard${query}`);
}

/**
 * 전체 관리자 신고 목록을 페이지 단위로 조회하는 API
 * 상태 필터를 생략하면 모든 신고 상태를 조회한다.
 * 목록 데이터와 페이지 메타데이터를 함께 반환한다.
 *
 * @param {{page?: number, size?: number, status?: string}} filters
 * @returns {Promise<any>} data와 meta를 포함한 결과
 */
export function listAdminReports({
         page = 0,
         size = 20,
        status
} = {}) {
    const query = buildQuery({page, size, status});

    return apiRequestWithMeta(`${ADMIN_API}/reports${query}`);
}

/**
 * 신고 ID로 신고 상세 정보와 처리 상태를 조회하는 API
 * 신고자, 피신고자 및 처리 이력을 확인할 때 사용한다.
 * 존재하지 않는 신고는 서버에서 404 오류가 반환된다.
 *
 * @param {number} reportId 신고 ID
 * @returns {Promise<any>} 신고 상세 데이터
 */
export function getAdminReport(reportId) {
    return apiRequest(`${ADMIN_API}/reports/${reportId}`);
}

/**
 *  접수된 신고를 기각하고 조치 사유를 기록하는 API
 *  서버가 RECEIVED 상태 및 동시 처리 여부를 최종 검사한다.
 *  성공 시 변경된 신고 상세 데이터를 반환한다.
 *
 * @param {number} reportId 신고 ID
 * @param {string} reason 기각 사유
 * @returns {Promise<any>} 처리 결과
 */
export function dismissReport(reportId, reason) {
    return apiRequest(`${ADMIN_API}/reports/${reportId}/dismissals`, {
        method: 'POST',
        body: {
            reason
        }
    });
}

/**
 * 신고를 근거로 해당 그룹의 피신고자를 강퇴하는 API
 * 사유를 서버에 전달하고, 강퇴 가능 여부는 서버에서 재판단한다.
 * 성공하면 처리된 신고 상세 정보를 반환한다.
 *
 * @param {number} reportId 신고 ID
 * @param {string} reason 강퇴 사유
 * @returns {Promise<any>} 처리 결과
 */
export function removeReportedMember(reportId, reason) {
    return apiRequest(`${ADMIN_API}/reports/${reportId}/removals`, {
        method: 'POST',
        body: {
            reason
        }
    });
}

/**
 * 해결되지 않은 운영 경고 목록을 조회하는 API
 * 유형별 필터와 페이지 조건을 서버에 전달한다.
 * 반환값에 현재 유효한 경고와 페이지 정보가 포함된다.
 *
 * @param {{page?: number, size?: number, type?: string}} filters
 * @returns {Promise<any>} data와 meta를 포함한 결과
 */
export function listOperationalWarnings({
        page = 0,
        size = 20,
        type
} = {}) {
    const query = buildQuery({page, size, type});

    return apiRequestWithMeta(`${ADMIN_API}/operations/warnings${query}`);
}

/**
 * 전체 관리자 권한으로 그룹의 주 관리자를 재지정하는 API
 * 대상 사용자 ID와 변경 사유를 JSON Body로 전달한다.
 * 서버는 활성 회원 여부와 동시 변경 충돌을 검사한다.
 *
 * @param {number} groupId 그룹 ID
 * @param {number} targetUserId 새 주 관리자 사용자 ID
 * @param {string} reason 변경 사유
 * @returns {Promise<any>} 변경된 그룹 정보
 */
export function transferPrimaryAdminAsSuperAdmin(groupId, targetUserId, reason) {
    return apiRequest(`${ADMIN_API}/groups/${groupId}/primary-transfer`, {
        method: 'POST',
        body: {
            targetUserId,
            reason
        }
    });
}


