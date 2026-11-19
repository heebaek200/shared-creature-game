import test from 'node:test';
import assert from 'node:assert/strict';
import { createGroupLiveManager } from '../src/realtime/group-live-manager.js';

const nextTurn = () => new Promise((resolve) => setTimeout(resolve, 0));
const snapshot = () => ({
    groupId: 10, streamInstanceId: 'stream', sequence: 0,
    onlineMembers: [], creature: { growthStage: 'CHILD' },
});

function setup(t, loadSnapshot = async () => snapshot()) {
    const clients = [];
    let loads = 0;
    const manager = createGroupLiveManager({
        loadSnapshot: async () => { loads += 1; return loadSnapshot(); },
        pollIntervalMs: 20,
        createClient(options) {
            const client = { options, disconnects: 0, connect() {},
                async reconnect() {}, async disconnect() { this.disconnects += 1; } };
            clients.push(client);
            return client;
        },
    });
    t.after(() => manager.stop());
    return { manager, clients, loads: () => loads };
}

async function waitFor(predicate) {
    for (let attempt = 0; attempt < 100; attempt += 1) {
        if (predicate()) return;
        await new Promise((resolve) => setTimeout(resolve, 5));
    }
    assert.fail('상태가 제한 시간 안에 변경되지 않았습니다.');
}

test('화면 구독이 없어도 폴링을 유지하고 새 헤더에 같은 상태를 전달한다', async (t) => {
    const { manager, clients, loads } = setup(t);
    await manager.start({ userId: 1, groupId: 10 });
    const first = [];
    const unsubscribe = manager.subscribeDisplayStatus(({ status }) => first.push(status));
    clients[0].options.onDisconnected();
    await nextTurn();
    assert.equal(first.at(-1), 'polling');
    unsubscribe();
    const previousLoads = loads();
    await waitFor(() => loads() > previousLoads);
    const second = [];
    manager.subscribeDisplayStatus(({ status }) => second.push(status));
    assert.equal(second.at(-1), 'polling');
    await manager.start({ userId: 1, groupId: 10 });
    assert.equal(clients.length, 1);
});

test('조회 오류는 반복 연결 종료에 덮어써지지 않고 조회 복구 시 폴링으로 돌아온다', async (t) => {
    let failing = true;
    const { manager, clients } = setup(t, async () => {
        if (failing) throw new Error('조회 실패');
        return snapshot();
    });
    await manager.start({ userId: 1, groupId: 10 });
    clients[0].options.onDisconnected();
    await waitFor(() => manager.getState().status === 'error');
    clients[0].options.onDisconnected();
    assert.equal(manager.getState().status, 'error');
    failing = false;
    await waitFor(() => manager.getState().status === 'polling');
});

test('재연결 후 조회까지 완료해야 live가 되고 폴링이 중단된다', async (t) => {
    const { manager, clients, loads } = setup(t);
    await manager.start({ userId: 1, groupId: 10 });
    clients[0].options.onDisconnected();
    await nextTurn();
    clients[0].options.onSubscribed();
    assert.equal(manager.getState().status, 'connecting');
    await waitFor(() => manager.getState().status === 'live');
    const previousLoads = loads();
    await new Promise((resolve) => setTimeout(resolve, 50));
    assert.equal(loads(), previousLoads);
});

test('화면 밖에서 받은 이벤트도 저장하고 재진입 구독에 최신 아바타를 전달한다', async (t) => {
    const { manager, clients } = setup(t);
    await manager.start({ userId: 1, groupId: 10 });
    clients[0].options.onSubscribed();
    await waitFor(() => manager.getState().status === 'live');
    clients[0].options.onEvent({ groupId: 10, streamInstanceId: 'stream', sequence: 1,
        eventId: 'joined', eventType: 'PRESENCE_JOINED',
        payload: { userId: 2, nickname: '테스트', equipments: { HEAD: null } } });
    let latest;
    manager.subscribeSnapshots((value) => { latest = value; });
    assert.equal(latest.onlineMembers[0].userId, 2);
});

test('로그아웃 뒤 늦게 도착한 조회와 연결 이벤트는 무시한다', async (t) => {
    let finish;
    const { manager, clients } = setup(t, () => new Promise((resolve) => { finish = resolve; }));
    await manager.start({ userId: 1, groupId: 10 });
    const received = [];
    manager.subscribeSnapshots((value) => received.push(value));
    clients[0].options.onSubscribed();
    await manager.stop();
    finish(snapshot());
    await nextTurn();
    clients[0].options.onSubscribed();
    assert.equal(received.length, 0);
    assert.equal(manager.getState().status, 'disconnected');
    assert.equal(clients[0].disconnects, 1);
});

test('어느 화면이든 공통 조회의 세션 만료를 전달하고 폴링을 중단한다', async (t) => {
    const { manager, clients, loads } = setup(t, async () => {
        throw Object.assign(new Error('세션 만료'), { code: 'SESSION_EXPIRED' });
    });
    const errors = [];
    manager.subscribeErrors((error) => errors.push(error));
    await manager.start({ userId: 1, groupId: 10 });
    clients[0].options.onDisconnected();
    await waitFor(() => errors.length === 1);
    clients[0].options.onDisconnected();
    const previousLoads = loads();
    await new Promise((resolve) => setTimeout(resolve, 50));
    assert.equal(errors[0].code, 'SESSION_EXPIRED');
    assert.equal(loads(), previousLoads);
});

test('WebSocket은 살아 있어도 조회 실패를 표시하고 주기적 조회 성공 후 복구한다', async (t) => {
    let failing = true;
    const { manager, clients } = setup(t, async () => {
        if (failing) throw new Error('일시적인 조회 오류');
        return snapshot();
    });
    await manager.start({ userId: 1, groupId: 10 });
    clients[0].options.onSubscribed();
    await waitFor(() => manager.getState().status === 'error');
    failing = false;
    await waitFor(() => manager.getState().status === 'live');
});

test('그룹 변경 후 이전 그룹의 지연 응답과 이벤트가 새 상태를 덮어쓰지 않는다', async (t) => {
    let finish;
    let calls = 0;
    const { manager, clients } = setup(t, () => {
        calls += 1;
        if (calls === 1) return new Promise((resolve) => { finish = resolve; });
        return Promise.resolve({ ...snapshot(), groupId: 20 });
    });
    await manager.start({ userId: 1, groupId: 10 });
    clients[0].options.onSubscribed();
    await manager.start({ userId: 1, groupId: 20 });
    clients[1].options.onSubscribed();
    await waitFor(() => manager.getState().status === 'live');
    finish(snapshot());
    clients[0].options.onDisconnected();
    await nextTurn();
    assert.equal(manager.getSnapshot().groupId, 20);
    assert.equal(manager.getState().status, 'live');
    assert.equal(clients[0].disconnects, 1);
});

for (const code of ['CSRF_INVALID', 'INVALID_REQUEST']) {
    test(`${code} 후 명시적 재시도는 동기화를 다시 만들고 조회·이벤트·폴링을 복구한다`, async (t) => {
        const { manager, clients, loads } = setup(t);
        await manager.start({ userId: 1, groupId: 10 });
        const { options } = clients[0];
        options.onSubscribed();
        await waitFor(() => manager.getState().status === 'live');
        options.onError(code, new Error('연결 승인 실패'));
        assert.equal(manager.getState().status, 'error');
        const before = loads();
        let reconnects = 0;
        clients[0].reconnect = async () => {
            reconnects += 1;
            options.onSubscribed();
        };
        await manager.reconnect();
        await waitFor(() => manager.getState().status === 'live');
        assert.equal(reconnects, 1);
        assert.ok(loads() > before);
        assert.equal(manager.getState().errorCode, null);
        options.onEvent({ groupId: 10, streamInstanceId: 'stream', sequence: 1,
            eventId: 'after-retry', eventType: 'PRESENCE_JOINED',
            payload: { userId: 3, nickname: '복구', equipments: {} } });
        assert.equal(manager.getSnapshot().onlineMembers[0].userId, 3);
        options.onDisconnected();
        await waitFor(() => manager.getState().status === 'polling');
    });
}

test('세션 만료 후 명시적 재시도도 인증 처리를 우회하지 않는다', async (t) => {
    const { manager, clients, loads } = setup(t);
    await manager.start({ userId: 1, groupId: 10 });
    clients[0].options.onError('SESSION_EXPIRED', new Error('세션 만료'));
    let reconnects = 0;
    clients[0].reconnect = async () => { reconnects += 1; };
    await manager.reconnect();
    assert.equal(reconnects, 0);
    assert.equal(loads(), 0);
    assert.equal(manager.getState().errorCode, 'SESSION_EXPIRED');
});
