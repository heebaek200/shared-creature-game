// @ts-check
import { Client } from '@stomp/stompjs';
import {
    clearCsrfToken,
    getCsrfToken,
} from '../api/client.js';

const useMock = import.meta.env.VITE_USE_MOCK_API === 'true';
const RECONNECT_DELAY_MS = 5000;

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
 */
export function createGroupLiveClient(options) {
    let disposed = false;
    let subscription = null;
    let terminalError = false;
    let csrfRetryUsed = false;
    let reconnectPromise = null;

    const protocol = location.protocol === 'https:' ? 'wss:' : 'ws:';
    const brokerURL = `${protocol}//${location.host}/ws`;
    const destination = `/topic/groups/${options.groupId}/live`;

    const client = new Client({
        brokerURL,
        reconnectDelay: RECONNECT_DELAY_MS,
        connectionTimeout: 8000,
        heartbeatIncoming: 0,
        heartbeatOutgoing: 0,
        debug: () => {},
    });

    client.beforeConnect = async () => {
        client.connectHeaders = {
            'X-CSRF-TOKEN': await getCsrfToken(),
        };
    };

    client.onConnect = () => {
        if (disposed) return;
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
        if (useMock) {
            options.onDisconnected();
            return Promise.resolve();
        }
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
            if (useMock) {
                options.onDisconnected();
                return;
            }
            if (!client.active) client.activate();
        },

        /** 사용자 재시도에서 캐시된 CSRF를 폐기하고 연결을 새로 시작합니다. */
        reconnect() {
            if (disposed) return Promise.resolve();
            csrfRetryUsed = false;
            clearCsrfToken();
            return restartConnection();
        },

        /** 구독과 재연결을 중단하고 WebSocket을 종료합니다. */
        async disconnect() {
            if (disposed) return;
            disposed = true;
            subscription?.unsubscribe();
            subscription = null;
            await client.deactivate();
        },
    };
}
