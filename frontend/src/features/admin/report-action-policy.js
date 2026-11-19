// @ts-check

/**
 * 신고 상태와 현재 멤버십을 기준으로 관리자에게 노출할 처리 동작을 결정합니다.
 * @param {{status?: string, reportedUserActive?: boolean}|null|undefined} report 신고 상세
 * @returns {{dismiss: boolean, remove: boolean, resolve: boolean}} 동작별 노출 여부
 */
export function reportActionAvailability(report) {
    const received = report?.status === 'RECEIVED';
    return {
        dismiss: received,
        remove: received && report?.reportedUserActive === true,
        resolve: received && report?.reportedUserActive === false,
    };
}
