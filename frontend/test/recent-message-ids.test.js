import assert from 'node:assert/strict';
import test from 'node:test';
import {createRecentMessageIdRegistry} from '../src/realtime/recent-message-ids.js';

test('최근 메시지 ID의 중복을 거부한다', () => {
    const registry = createRecentMessageIdRegistry(3);
    assert.equal(registry.remember('a'), true);
    assert.equal(registry.remember('a'), false);
    assert.equal(registry.size(), 1);
});

test('상한을 넘으면 가장 오래된 메시지 ID부터 제거한다', () => {
    const registry = createRecentMessageIdRegistry(2);
    registry.remember('a');
    registry.remember('b');
    registry.remember('c');

    assert.equal(registry.size(), 2);
    assert.equal(registry.remember('b'), false);
    assert.equal(registry.remember('a'), true);
    assert.equal(registry.size(), 2);
});

test('페이지 정리 시 보관한 메시지 ID를 비운다', () => {
    const registry = createRecentMessageIdRegistry(2);
    registry.remember('a');
    registry.clear();
    assert.equal(registry.size(), 0);
});
