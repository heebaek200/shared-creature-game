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

/**
 * CSS Transition 도중 계산된 px 좌표를 필드 기준 백분율로 변환합니다.
 * 레이아웃 크기나 계산값을 얻지 못하면 마지막 내부 좌표를 유지합니다.
 *
 * @param {{
 *   left: string,
 *   top: string,
 *   fieldWidth: number,
 *   fieldHeight: number,
 *   fallbackX: number,
 *   fallbackY: number
 * }} options
 * @returns {{x: number, y: number}}
 */
export function calculateRenderedPosition(options) {
    const left = Number.parseFloat(options.left);
    const top = Number.parseFloat(options.top);
    const x = Number.isFinite(left) && options.fieldWidth > 0
        ? left / options.fieldWidth * 100
        : options.fallbackX;
    const y = Number.isFinite(top) && options.fieldHeight > 0
        ? top / options.fieldHeight * 100
        : options.fallbackY;

    return {
        x: Math.min(100, Math.max(0, x)),
        y: Math.min(100, Math.max(0, y)),
    };
}
