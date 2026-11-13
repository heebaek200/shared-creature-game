const MINUTE_MS = 60 * 1000;

/**
 * @param {{ seasonNumber?: number, endAt?: string|null }} seasonData
 * @param {number} [now]
 */
export function formatSeasonRemainingTime(seasonData, now = Date.now()) {
    if (!seasonData) return '시즌 정보 확인 불가';

    const endAtTime = seasonData.endAt
        ? new Date(seasonData.endAt).getTime()
        : NaN;

    let remainingText = '종료됨';
    const diffMs = endAtTime - now;

    if (Number.isFinite(endAtTime) && diffMs > 0) {
        const diffHours = diffMs / (60 * 60 * 1000);

        if (diffHours >= 24) {
            remainingText = `${Math.ceil(diffHours / 24)}일 남음`;
        } else if (diffHours >= 1) {
            remainingText = `${Math.ceil(diffHours)}시간 남음`;
        } else {
            remainingText = `시즌 종료까지 ${Math.ceil(diffMs / MINUTE_MS)}분`;
        }
    }

    return `시즌 ${seasonData.seasonNumber || 1} · ${remainingText}`;
}

/**
 * 즉시 갱신하고 이후 1분마다 갱신합니다.
 * @param {{ seasonNumber?: number, endAt?: string|null }} seasonData
 * @param {(text: string) => void} onUpdate
 */
export function startSeasonCountdown(seasonData, onUpdate) {
    const update = () => {
        onUpdate(formatSeasonRemainingTime(seasonData));
    };
    update();
    if (!seasonData) {
        return () => {};
    }
    const intervalId = window.setInterval(update, MINUTE_MS);
    return () => window.clearInterval(intervalId);
}