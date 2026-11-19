// @ts-check

/**
 * 최근 메시지 ID만 보관하여 재전송 중복은 막고 장시간 접속의 메모리 사용은 제한합니다.
 * @param {number} limit 보관할 최대 ID 수
 * @returns {{remember: (messageId: string) => boolean, clear: () => void, size: () => number}}
 */
export function createRecentMessageIdRegistry(limit = 512) {
    if (!Number.isInteger(limit) || limit < 1) {
        throw new RangeError('limit은 1 이상의 정수여야 합니다.');
    }
    const ids = new Set();
    const order = [];

    return {
        remember(messageId) {
            if (ids.has(messageId)) return false;
            ids.add(messageId);
            order.push(messageId);
            if (order.length > limit) ids.delete(order.shift());
            return true;
        },
        clear() {
            ids.clear();
            order.length = 0;
        },
        size() {
            return ids.size;
        },
    };
}
