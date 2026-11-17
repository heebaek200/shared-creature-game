import test from 'node:test';
import assert from 'node:assert/strict';
import {
    calculateIdleWaitScale,
    calculateRenderedPosition,
} from '../src/features/creature/avatar-movement-policy.js';

test('접속자가 적을수록 아바타 이동 휴식 시간 배율이 작아진다', () => {
    const solo = calculateIdleWaitScale(1);
    const middle = calculateIdleWaitScale(10);
    const full = calculateIdleWaitScale(20);

    assert.equal(solo, 0.2);
    assert.ok(solo < middle);
    assert.ok(middle < full);
    assert.equal(full, 1);
    assert.equal(calculateIdleWaitScale(100), 1);
});

test('이동 중 화면 좌표를 필드 백분율 좌표로 고정한다', () => {
    assert.deepEqual(calculateRenderedPosition({
        left: '240px',
        top: '90px',
        fieldWidth: 800,
        fieldHeight: 600,
        fallbackX: 10,
        fallbackY: 20,
    }), { x: 30, y: 15 });
});

test('화면 좌표를 계산할 수 없으면 마지막 내부 좌표를 유지한다', () => {
    assert.deepEqual(calculateRenderedPosition({
        left: 'auto',
        top: 'auto',
        fieldWidth: 0,
        fieldHeight: 0,
        fallbackX: 18,
        fallbackY: 27,
    }), { x: 18, y: 27 });
});
