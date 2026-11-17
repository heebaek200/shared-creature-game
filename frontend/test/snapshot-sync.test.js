import test from 'node:test';
import assert from 'node:assert/strict';
import { createSnapshotSync } from '../src/realtime/snapshot-sync.js';

const streamId = '11111111-1111-4111-8111-111111111111';

function snapshot(sequence) {
    return { streamInstanceId: streamId, sequence };
}

function event(eventId, sequence, overrides = {}) {
    return {
        eventId,
        streamInstanceId: streamId,
        sequence,
        eventType: 'CREATURE_UPDATED',
        payload: {},
        ...overrides,
    };
}

async function nextTurn() {
    await new Promise((resolve) => setTimeout(resolve, 0));
}

test('정상 이벤트를 한 번만 적용한다', async () => {
    const appliedEvents = [];
    const sync = createSnapshotSync({
        loadSnapshot: async () => snapshot(10),
        applySnapshot: () => {},
        applyEvent: (received) => appliedEvents.push(received.eventId),
    });

    await sync.recoverRealtime();
    sync.handleEvent(event('event-11', 11));
    sync.handleEvent(event('event-11', 11));

    assert.deepEqual(appliedEvents, ['event-11']);
    sync.dispose();
});

test('sequence 누락과 stream 변경은 Payload를 적용하지 않고 Snapshot으로 복구한다', async () => {
    let snapshotLoads = 0;
    let eventApplications = 0;
    const sync = createSnapshotSync({
        loadSnapshot: async () => {
            snapshotLoads += 1;
            return snapshot(snapshotLoads === 1 ? 10 : 20);
        },
        applySnapshot: () => {},
        applyEvent: () => { eventApplications += 1; },
    });

    await sync.recoverRealtime();
    sync.handleEvent(event('event-gap', 13));
    await nextTurn();
    sync.handleEvent(event('event-stream', 21, {
        streamInstanceId: '22222222-2222-4222-8222-222222222222',
    }));
    await nextTurn();

    assert.equal(eventApplications, 0);
    assert.equal(snapshotLoads, 3);
    sync.dispose();
});

test('Snapshot 조회 중 이벤트는 refreshPending으로 한 번 더 조회한다', async () => {
    let resolveFirstSnapshot;
    let snapshotLoads = 0;
    const firstSnapshot = new Promise((resolve) => {
        resolveFirstSnapshot = resolve;
    });
    const sync = createSnapshotSync({
        loadSnapshot: async () => {
            snapshotLoads += 1;
            if (snapshotLoads === 1) return firstSnapshot;
            return snapshot(12);
        },
        applySnapshot: () => {},
        applyEvent: () => assert.fail('조회 중 이벤트 Payload를 적용하면 안 됩니다.'),
    });

    const recovery = sync.recoverRealtime();
    sync.handleEvent(event('event-during-snapshot', 11));
    resolveFirstSnapshot(snapshot(10));
    await recovery;

    assert.equal(snapshotLoads, 2);
    sync.dispose();
});

test('Snapshot sequence 이하로 지연 도착한 미처리 이벤트는 재조회한다', async () => {
    let snapshotLoads = 0;
    let eventApplications = 0;
    const sync = createSnapshotSync({
        loadSnapshot: async () => {
            snapshotLoads += 1;
            return snapshot(10);
        },
        applySnapshot: () => {},
        applyEvent: () => { eventApplications += 1; },
    });

    await sync.recoverRealtime();
    sync.handleEvent(event('late-event', 9));
    await nextTurn();

    assert.equal(eventApplications, 0);
    assert.equal(snapshotLoads, 2);
    sync.dispose();
});

test('이벤트 적용 결과가 순위 교정을 요청하면 Snapshot을 재조회한다', async () => {
    let snapshotLoads = 0;
    const sync = createSnapshotSync({
        loadSnapshot: async () => {
            snapshotLoads += 1;
            return snapshot(snapshotLoads === 1 ? 10 : 11);
        },
        applySnapshot: () => {},
        applyEvent: () => ({ refresh: true }),
    });

    await sync.recoverRealtime();
    sync.handleEvent(event('contribution-event', 11, {
        eventType: 'CONTRIBUTION_UPDATED',
    }));
    await nextTurn();

    assert.equal(snapshotLoads, 2);
    sync.dispose();
});
