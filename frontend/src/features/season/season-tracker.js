import {getCurrentSeason} from './api.js';
import {startSeasonCountdown} from './season-countdown.js';

const REFRESH_INTERVAL_MS = 60 * 1000;

let currentText = '로딩 중...';
let currentSeason = null;
let pendingRequest = null;
let lastAttemptAt = -Infinity;
let stopCountdown = null;
let onUpdate = () => {};
let initialized = false;
let retryTimerId = null;
let generation = 0;

function publish(text) {
    currentText = text;
    onUpdate(text);
}

function clearRetryTimer() {
    if (retryTimerId !== null) {
        window.clearTimeout(retryTimerId);
        retryTimerId = null;
    }
}

function scheduleRetry() {
    if (!initialized || retryTimerId !== null) return;

    retryTimerId = window.setTimeout(() => {
        retryTimerId = null;
        void refreshSeason();
    }, REFRESH_INTERVAL_MS);
}

function handleVisibilityChange() {
    if (document.visibilityState === 'visible') {
        void refreshSeason();
    }
}

/**
 * 시즌 조회 결과의 반영과 이후 갱신을 중단합니다.
 * 진행 중인 HTTP 요청 자체를 취소하지는 않지만, 그 결과는 무시합니다.
 */
export function stopSeasonTracker() {
    initialized = false;
    generation++;

    clearRetryTimer();
    stopCountdown?.();
    stopCountdown = null;

    document.removeEventListener(
        'visibilitychange',
        handleVisibilityChange
    );

    pendingRequest = null;
    currentSeason = null;
    currentText = '로딩 중...';
    lastAttemptAt = -Infinity;
    onUpdate = () => {};
}

/**
 * 현재 시즌을 조회하고 카운트다운을 교체합니다.
 * 인증·권한 오류는 갱신을 중단하고, 그 외 실패는 재시도합니다.
 */
function refreshSeason() {
    if (!initialized) return;
    if (pendingRequest) return pendingRequest;
    if (Date.now() - lastAttemptAt < REFRESH_INTERVAL_MS) return;

    clearRetryTimer();
    lastAttemptAt = Date.now();

    const requestGeneration = generation;
    const isCurrent = () =>
        initialized && requestGeneration === generation;

    pendingRequest = Promise.resolve()
        .then(() => {
            if (!isCurrent()) return null;
            return getCurrentSeason();
        })
        .then((response) => {
            if (!isCurrent()) return;

            const season = response?.data ?? response;
            if (!season) {
                throw new Error('현재 시즌 정보가 없습니다.');
            }

            stopCountdown?.();
            currentSeason = season;

            stopCountdown = startSeasonCountdown(season, (text) => {
                if (!isCurrent()) return;

                publish(text);

                const endAt = season.endAt
                    ? new Date(season.endAt).getTime()
                    : NaN;

                if (Number.isFinite(endAt) && Date.now() >= endAt) {
                    void refreshSeason();
                }
            });
        })
        .catch((error) => {
            if (!isCurrent()) return;

            if (error?.status === 401 || error?.status === 403) {
                const update = onUpdate;
                stopSeasonTracker();
                update('시즌 정보 확인 불가');
                return;
            }

            if (!currentSeason) {
                publish('시즌 정보 확인 불가');
            }

            scheduleRetry();
        })
        .finally(() => {
            // 이전 요청이 새 Tracker의 요청 상태를 지우지 않게 합니다.
            if (requestGeneration === generation) {
                pendingRequest = null;
            }
        });

    return pendingRequest;
}

/**
 * 시즌 갱신을 시작하거나 기존 Tracker의 표시 콜백을 교체합니다.
 * @param {(text: string) => void} update 시즌 문구 표시 함수
 * @returns {string} 현재 시즌 표시 문구
 */
export function initializeSeasonTracker(update) {
    onUpdate = update;

    if (!initialized) {
        initialized = true;
        document.addEventListener(
            'visibilitychange',
            handleVisibilityChange
        );
    }

    void refreshSeason();
    return currentText;
}