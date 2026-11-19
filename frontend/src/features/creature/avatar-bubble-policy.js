// @ts-check

/**
 * 포인터와 키보드 상호작용이 모두 끝났을 때만 말풍선 만료 타이머를 진행합니다.
 * @param {{hovered: boolean, focused: boolean}} interaction 현재 상호작용 상태
 * @returns {boolean} 타이머 진행 여부
 */
export function shouldRunBubbleTimer(interaction) {
    return !interaction.hovered && !interaction.focused;
}

/**
 * 현재 말풍선 위치를 필드 내부로 보정할 다음 수평 이동량을 계산합니다.
 * @param {{fieldLeft: number, fieldRight: number, bubbleLeft: number, bubbleRight: number, currentShift: number}} bounds 경계 좌표
 * @returns {number} CSS에 적용할 수평 이동 픽셀
 */
export function calculateBubbleHorizontalShift(bounds) {
    const fieldWidth = bounds.fieldRight - bounds.fieldLeft;
    const bubbleWidth = bounds.bubbleRight - bounds.bubbleLeft;
    if (bubbleWidth >= fieldWidth || bounds.bubbleLeft < bounds.fieldLeft) {
        return bounds.currentShift + bounds.fieldLeft - bounds.bubbleLeft;
    }
    if (bounds.bubbleRight > bounds.fieldRight) {
        return bounds.currentShift - (bounds.bubbleRight - bounds.fieldRight);
    }
    return bounds.currentShift;
}
