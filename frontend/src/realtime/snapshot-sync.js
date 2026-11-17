// @ts-check

const DEFAULT_POLL_INTERVAL_MS = 30_000;
const MAX_SEEN_EVENT_IDS = 500;

/**
 * REST Snapshot과 WebSocket Stream Cursor를 동기화합니다.
 * 이벤트 Payload Buffer 없이 refreshPending으로 동시 복구 요청을 병합합니다.
 *
 * @param {{
 *   loadSnapshot: () => Promise<Record<string, any>>,
 *   applySnapshot: (snapshot: Record<string, any>) => void,
 *   applyEvent: (event: Record<string, any>) => ({refresh?: boolean}|void),
 *   onStatusChange?: (status: 'connecting'|'live'|'polling'|'error') => void,
 *   onError?: (error: unknown) => void,
 *   pollIntervalMs?: number
 * }} options
 */
export function createSnapshotSync(options) {
    let disposed = false;
    let snapshotInFlight = null;
    let refreshPending = false;
    let pollingTimer = null;
    let connectionGeneration = 0;
    let streamInstanceId = null;
    let sequence = null;
    const seenEventIds = new Set();

    function rememberEvent(eventId) {
        if (!eventId) return;
        seenEventIds.add(eventId);
        if (seenEventIds.size > MAX_SEEN_EVENT_IDS) {
            const oldest = seenEventIds.values().next().value;
            if (oldest !== undefined) seenEventIds.delete(oldest);
        }
    }

    async function loadUntilStable() {
        do {
            refreshPending = false;
            const snapshot = await options.loadSnapshot();
            if (disposed) return false;

            options.applySnapshot(snapshot);
            streamInstanceId = snapshot.streamInstanceId;
            sequence = snapshot.sequence;
        } while (!disposed && refreshPending);

        return !disposed;
    }

    function refreshSnapshot() {
        if (disposed) return Promise.resolve(false);
        if (snapshotInFlight) {
            refreshPending = true;
            return snapshotInFlight;
        }

        snapshotInFlight = loadUntilStable()
            .catch((error) => {
                if (!disposed) {
                    options.onStatusChange?.('error');
                    options.onError?.(error);
                }
                return false;
            })
            .finally(() => {
                snapshotInFlight = null;
            });

        return snapshotInFlight;
    }

    return {
        /** 사용자의 명시적 재시도 또는 화면 내부 복구 요청으로 Snapshot을 조회합니다. */
        refresh() {
            return refreshSnapshot();
        },

        /** 구독 완료 후 Snapshot을 복구하고 Polling을 중단합니다. */
        async recoverRealtime() {
            if (disposed) return false;
            const recoveryGeneration = ++connectionGeneration;
            options.onStatusChange?.('connecting');
            const recovered = await refreshSnapshot();
            if (
                recovered
                && !disposed
                && recoveryGeneration === connectionGeneration
            ) {
                if (pollingTimer !== null) {
                    globalThis.clearInterval(pollingTimer);
                    pollingTimer = null;
                }
                options.onStatusChange?.('live');
            }
            return recovered;
        },

        /** 연결 장애 중 즉시 Snapshot을 복구하고 이후 30초 Polling을 수행합니다. */
        startPolling() {
            if (disposed) return;
            connectionGeneration += 1;
            options.onStatusChange?.('polling');
            void refreshSnapshot();
            if (pollingTimer === null) {
                pollingTimer = globalThis.setInterval(
                    () => void refreshSnapshot(),
                    options.pollIntervalMs ?? DEFAULT_POLL_INTERVAL_MS,
                );
            }
        },

        /** 이벤트의 중복·누락·Stream 변경을 검사하고 적용합니다. */
        handleEvent(event) {
            if (disposed || !event || typeof event !== 'object') return;

            const eventId = event.eventId;
            if (eventId && seenEventIds.has(eventId)) return;

            if (snapshotInFlight || streamInstanceId === null || sequence === null) {
                rememberEvent(eventId);
                refreshPending = true;
                return;
            }

            if (event.streamInstanceId !== streamInstanceId) {
                rememberEvent(eventId);
                void refreshSnapshot();
                return;
            }

            if (!Number.isSafeInteger(event.sequence)) {
                rememberEvent(eventId);
                void refreshSnapshot();
                return;
            }

            if (event.sequence <= sequence) {
                rememberEvent(eventId);
                void refreshSnapshot();
                return;
            }

            if (event.sequence !== sequence + 1) {
                rememberEvent(eventId);
                void refreshSnapshot();
                return;
            }

            rememberEvent(eventId);
            sequence = event.sequence;
            try {
                const result = options.applyEvent(event);
                if (result?.refresh) void refreshSnapshot();
            } catch (error) {
                options.onError?.(error);
                void refreshSnapshot();
            }
        },

        /** 현재 진행 중인 요청의 결과 반영과 Polling을 중단합니다. */
        dispose() {
            disposed = true;
            connectionGeneration += 1;
            refreshPending = false;
            if (pollingTimer !== null) {
                globalThis.clearInterval(pollingTimer);
                pollingTimer = null;
            }
        },
    };
}
