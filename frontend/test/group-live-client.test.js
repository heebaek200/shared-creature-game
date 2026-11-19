import test from 'node:test';
import assert from 'node:assert/strict';
import { createGroupLiveClient } from '../src/realtime/group-live-client.js';
import { createSnapshotSync } from '../src/realtime/snapshot-sync.js';

const streamId = '11111111-1111-4111-8111-111111111111';

async function nextTurn() {
    await new Promise((resolve) => setTimeout(resolve, 0));
}

function createFakeStompClient() {
    return {
        active: false,
        reconnectDelay: 0,
        connectHeaders: {},
        activationCount: 0,
        webSocketOpenCount: 0,
        subscriptions: [],
        activate() {
            if (this.active) return;
            this.active = true;
            this.activationCount += 1;
            void this.beforeConnect().then(() => {
                if (this.active) this.webSocketOpenCount += 1;
            });
        },
        async deactivate() {
            this.active = false;
        },
        subscribe(destination, callback) {
            const subscription = {
                destination,
                callback,
                unsubscribed: false,
                unsubscribe() {
                    this.unsubscribed = true;
                },
            };
            this.subscriptions.push(subscription);
            return subscription;
        },
        emitConnect() {
            this.onConnect({});
        },
    };
}

function createFakeTimers() {
    let nextId = 1;
    const timers = new Map();
    return {
        setTimeout(callback, delay) {
            const id = nextId;
            nextId += 1;
            timers.set(id, { callback, delay });
            return id;
        },
        clearTimeout(id) {
            timers.delete(id);
        },
        runNext() {
            const entry = timers.entries().next().value;
            if (!entry) return false;
            const [id, timer] = entry;
            timers.delete(id);
            timer.callback();
            return true;
        },
        get size() {
            return timers.size;
        },
        delays() {
            return Array.from(timers.values(), (timer) => timer.delay);
        },
    };
}

function createClientHarness(overrides = {}) {
    const stompClient = createFakeStompClient();
    const timers = createFakeTimers();
    const errors = [];
    let disconnectedCount = 0;
    let subscribedCount = 0;
    const client = createGroupLiveClient({
        groupId: 1,
        onEvent: () => {},
        onSubscribed: () => { subscribedCount += 1; },
        onDisconnected: () => { disconnectedCount += 1; },
        onError: (code, error) => errors.push({ code, error }),
        ...overrides.options,
    }, {
        createClient: () => stompClient,
        getCsrfToken: overrides.getCsrfToken ?? (async () => 'csrf-token'),
        clearCsrfToken: () => {},
        setTimeout: timers.setTimeout.bind(timers),
        clearTimeout: timers.clearTimeout.bind(timers),
        location: { protocol: 'http:', host: 'localhost' },
        useMock: false,
    });

    return {
        client,
        stompClient,
        timers,
        errors,
        disconnectedCount: () => disconnectedCount,
        subscribedCount: () => subscribedCount,
    };
}

test('CSRF 조회 성공 후 STOMP WebSocket 연결을 시작한다', async () => {
    const harness = createClientHarness();

    harness.client.connect();
    await nextTurn();

    assert.equal(harness.stompClient.webSocketOpenCount, 1);
    assert.deepEqual(harness.stompClient.connectHeaders, {
        'X-CSRF-TOKEN': 'csrf-token',
    });
    harness.stompClient.emitConnect();
    assert.equal(harness.subscribedCount(), 1);
    await harness.client.disconnect();
});

for (const failure of [
    new TypeError('network unavailable'),
    { status: 503, code: 'SERVER_ERROR', message: 'temporarily unavailable' },
]) {
    test(`CSRF 조회 통신 실패(${failure.status ?? 'network'}) 시 Polling과 재연결을 시작한다`, async () => {
        const harness = createClientHarness({
            getCsrfToken: async () => { throw failure; },
        });

        harness.client.connect();
        await nextTurn();

        assert.equal(harness.disconnectedCount(), 1);
        assert.equal(harness.errors[0].code, 'CONNECTION_ERROR');
        assert.equal(harness.timers.size, 1);
        assert.deepEqual(harness.timers.delays(), [5000]);
        assert.equal(harness.stompClient.webSocketOpenCount, 0);
        await harness.client.disconnect();
    });
}

test('CSRF 조회의 세션 만료는 로그인 오류로 전달하고 재시도하지 않는다', async () => {
    const harness = createClientHarness({
        getCsrfToken: async () => {
            throw { status: 401, code: 'SESSION_EXPIRED' };
        },
    });

    harness.client.connect();
    await nextTurn();

    assert.equal(harness.errors[0].code, 'SESSION_EXPIRED');
    assert.equal(harness.disconnectedCount(), 0);
    assert.equal(harness.timers.size, 0);
    assert.equal(harness.stompClient.webSocketOpenCount, 0);
    await harness.client.disconnect();
});

test('CSRF 조회 정상화 후 STOMP와 Snapshot을 복구하고 Polling을 중단한다', async () => {
    let tokenRequests = 0;
    const statuses = [];
    const sync = createSnapshotSync({
        loadSnapshot: async () => ({ streamInstanceId: streamId, sequence: 0 }),
        applySnapshot: () => {},
        applyEvent: () => {},
        onStatusChange: (status) => statuses.push(status),
        pollIntervalMs: 1000,
    });
    const harness = createClientHarness({
        getCsrfToken: async () => {
            tokenRequests += 1;
            if (tokenRequests === 1) throw new TypeError('network unavailable');
            return 'recovered-token';
        },
        options: {
            onSubscribed: () => sync.recoverRealtime(),
            onDisconnected: () => sync.startPolling(),
        },
    });

    harness.client.connect();
    await nextTurn();
    assert.equal(statuses.at(-1), 'polling');

    assert.equal(harness.timers.runNext(), true);
    await nextTurn();
    assert.equal(harness.stompClient.webSocketOpenCount, 1);
    harness.stompClient.emitConnect();
    await nextTurn();

    assert.equal(statuses.at(-1), 'live');
    sync.dispose();
    await harness.client.disconnect();
});

test('CSRF 재시도 대기 중 페이지를 이탈하면 재연결 자원을 정리한다', async () => {
    const harness = createClientHarness({
        getCsrfToken: async () => { throw new TypeError('network unavailable'); },
    });

    harness.client.connect();
    await nextTurn();
    assert.equal(harness.timers.size, 1);

    await harness.client.disconnect();

    assert.equal(harness.timers.size, 0);
    assert.equal(harness.timers.runNext(), false);
    assert.equal(harness.stompClient.activationCount, 1);
});

test('CSRF 재조회가 반복해서 실패해도 재연결 Timer를 하나만 유지한다', async () => {
    const harness = createClientHarness({
        getCsrfToken: async () => { throw new TypeError('network unavailable'); },
    });

    harness.client.connect();
    await nextTurn();
    assert.equal(harness.timers.size, 1);

    assert.equal(harness.timers.runNext(), true);
    await nextTurn();

    assert.equal(harness.disconnectedCount(), 2);
    assert.equal(harness.timers.size, 1);
    await harness.client.disconnect();
});

test('CSRF 조회 중 페이지를 이탈하면 완료 결과로 연결하거나 재시도하지 않는다', async () => {
    let resolveToken;
    const pendingToken = new Promise((resolve) => {
        resolveToken = resolve;
    });
    const harness = createClientHarness({
        getCsrfToken: () => pendingToken,
    });

    harness.client.connect();
    await harness.client.disconnect();
    resolveToken('late-token');
    await nextTurn();

    assert.equal(harness.stompClient.webSocketOpenCount, 0);
    assert.equal(harness.timers.size, 0);
    assert.equal(harness.errors.length, 0);
    assert.equal(harness.disconnectedCount(), 0);
});
