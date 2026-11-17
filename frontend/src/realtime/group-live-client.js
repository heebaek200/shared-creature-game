// @ts-check
import { Client } from '@stomp/stompjs';
import {
    clearCsrfToken,
    getCsrfToken,
} from '../api/client.js';

const useMock = import.meta.env?.VITE_USE_MOCK_API === 'true';
const RECONNECT_DELAY_MS = 5000;
const ACCESS_ERROR_CODES = [
    'AUTH_REQUIRED',
    'SESSION_EXPIRED',
    'ROLE_FORBIDDEN',
];

/**
 * 현재 그룹의 Native WebSocket STOMP 연결과 단일 구독을 관리합니다.
 *
 * @param {{
 *   groupId: number|string,
 *   onEvent: (event: Record<string, any>) => void,
 *   onSubscribed: () => void|Promise<void>,
 *   onDisconnected: () => void,
 *   onError: (code: string, error?: unknown) => void
 * }} options
 * @param {{
 *   createClient?: (configuration: Record<string, any>) => any,
 *   getCsrfToken?: () => Promise<string>,
 *   clearCsrfToken?: () => void,
 *   setTimeout?: (callback: () => void, delay: number) => any,
 *   clearTimeout?: (timer: any) => void,
 *   location?: {protocol: string, host: string},
 *   useMock?: boolean
 * }} [dependencies] 테스트에서 브라우저·STOMP 의존성을 대체합니다.
 */
export function createGroupLiveClient(options, dependencies = {}) {
    let disposed = false;
    let subscription = null;
    let terminalError = false;
    let csrfRetryUsed = false;
    let reconnectPromise = null;
    let csrfRequestInFlight = false;
    let csrfRetryTimer = null;

    const runtimeLocation = dependencies.location ?? globalThis.location;
    const getConnectCsrfToken = dependencies.getCsrfToken ?? getCsrfToken;
    const clearConnectCsrfToken = dependencies.clearCsrfToken ?? clearCsrfToken;
    const scheduleTimeout = dependencies.setTimeout ?? globalThis.setTimeout;
    const cancelTimeout = dependencies.clearTimeout ?? globalThis.clearTimeout;
    const mockEnabled = dependencies.useMock ?? useMock;
    const protocol = runtimeLocation.protocol === 'https:' ? 'wss:' : 'ws:';
    const brokerURL = `${protocol}//${runtimeLocation.host}/ws`;
    const destination = `/topic/groups/${options.groupId}/live`;

    const createClient = dependencies.createClient
        ?? ((configuration) => new Client(configuration));
    const client = createClient({
        brokerURL,
        reconnectDelay: RECONNECT_DELAY_MS,
        connectionTimeout: 8000,
        heartbeatIncoming: 0,
        heartbeatOutgoing: 0,
        debug: () => {},
    });

    function clearCsrfRetryTimer() {
        if (csrfRetryTimer === null) return;
        cancelTimeout(csrfRetryTimer);
        csrfRetryTimer = null;
    }

    function scheduleCsrfRetry() {
        if (disposed || terminalError || csrfRetryTimer !== null) return;
        csrfRetryTimer = scheduleTimeout(() => {
            csrfRetryTimer = null;
            if (!disposed && !terminalError && !client.active) client.activate();
        }, RECONNECT_DELAY_MS);
    }

    client.beforeConnect = async () => {
        csrfRequestInFlight = true;
        try {
            const token = await getConnectCsrfToken();
            if (disposed) return;
            client.connectHeaders = { 'X-CSRF-TOKEN': token };
        } catch (error) {
            if (disposed) return;

            const code = typeof error?.code === 'string'
                ? error.code
                : 'CONNECTION_ERROR';
            const accessError = ACCESS_ERROR_CODES.includes(code);
            terminalError = accessError;
            clearConnectCsrfToken();
            clearCsrfRetryTimer();
            client.reconnectDelay = accessError ? 0 : RECONNECT_DELAY_MS;
            await client.deactivate();
            if (disposed) return;

            options.onError(accessError ? code : 'CONNECTION_ERROR', error);
            if (!accessError) {
                options.onDisconnected();
                scheduleCsrfRetry();
            }
        } finally {
            csrfRequestInFlight = false;
        }
    };

    client.onConnect = () => {
        if (disposed) return;
        clearCsrfRetryTimer();
        terminalError = false;
        csrfRetryUsed = false;
        subscription?.unsubscribe();
        subscription = client.subscribe(destination, (message) => {
            if (disposed) return;

            try {
                const event = JSON.parse(message.body);
                if (String(event.groupId) === String(options.groupId)) {
                    options.onEvent(event);
                }
            } catch (error) {
                options.onError('INVALID_MESSAGE', error);
            }
        });
        void options.onSubscribed();
    };

    client.onStompError = (frame) => {
        const code = frame.headers.message || 'INVALID_REQUEST';
        if (code === 'CSRF_INVALID' && !csrfRetryUsed) {
            csrfRetryUsed = true;
            clearCsrfToken();
            void restartConnection();
            return;
        }

        terminalError = [
            'AUTH_REQUIRED',
            'CSRF_INVALID',
            'ROLE_FORBIDDEN',
            'INVALID_REQUEST',
        ].includes(code);
        if (terminalError) {
            client.reconnectDelay = 0;
            void client.deactivate();
        }
        options.onError(code, frame);
    };

    client.onWebSocketError = (error) => {
        if (!disposed) options.onError('CONNECTION_ERROR', error);
    };

    client.onWebSocketClose = () => {
        if (disposed) return;
        subscription = null;
        if (!terminalError) options.onDisconnected();
    };

    /** 현재 연결을 완전히 종료한 뒤 새 CSRF·Session 정보로 다시 시작합니다. */
    function restartConnection() {
        if (disposed) return Promise.resolve();
        if (mockEnabled) {
            options.onDisconnected();
            return Promise.resolve();
        }
        if (csrfRequestInFlight) return Promise.resolve();
        if (reconnectPromise) return reconnectPromise;

        terminalError = true;
        reconnectPromise = (async () => {
            subscription?.unsubscribe();
            subscription = null;
            await client.deactivate();
            if (disposed) return;

            terminalError = false;
            client.reconnectDelay = RECONNECT_DELAY_MS;
            client.activate();
        })().catch((error) => {
            if (!disposed) options.onError('CONNECTION_ERROR', error);
        }).finally(() => {
            reconnectPromise = null;
        });
        return reconnectPromise;
    }

    return {
        /** 연결과 자동 재연결을 시작합니다. */
        connect() {
            if (disposed) return;
            if (mockEnabled) {
                options.onDisconnected();
                return;
            }
            if (!client.active) client.activate();
        },

        /** 사용자 재시도에서 캐시된 CSRF를 폐기하고 연결을 새로 시작합니다. */
        reconnect() {
            if (disposed) return Promise.resolve();
            csrfRetryUsed = false;
            clearConnectCsrfToken();
            return restartConnection();
        },

        /** 구독과 재연결을 중단하고 WebSocket을 종료합니다. */
        async disconnect() {
            if (disposed) return;
            disposed = true;
            clearCsrfRetryTimer();
            subscription?.unsubscribe();
            subscription = null;
            await client.deactivate();
        },
    };
}
