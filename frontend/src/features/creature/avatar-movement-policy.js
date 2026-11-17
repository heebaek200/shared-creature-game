// @ts-check

const REFERENCE_MEMBER_COUNT = 20;
const SOLO_IDLE_WAIT_SCALE = 0.2;

/**
 * 접속자가 적을수록 다음 이동까지의 휴식 시간을 줄이는 배율을 계산합니다.
 * 1명은 기준 시간의 20%, 20명 이상은 100%를 적용합니다.
 *
 * @param {number} memberCount 현재 접속자 수
 * @returns {number} 0.2~1 범위의 휴식 시간 배율
 */
export function calculateIdleWaitScale(memberCount) {
    const normalizedCount = Math.min(
        REFERENCE_MEMBER_COUNT,
        Math.max(1, Math.trunc(memberCount)),
    );
    const populationRatio = (normalizedCount - 1) / (REFERENCE_MEMBER_COUNT - 1);
    return SOLO_IDLE_WAIT_SCALE
        + (1 - SOLO_IDLE_WAIT_SCALE) * populationRatio;
}
